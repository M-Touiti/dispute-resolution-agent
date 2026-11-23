"""The load-test shim (task 2.13): HTTP in front of Kafka, for k6, which speaks HTTP and not Kafka.

``POST /disputes`` takes the next free golden dispute, clears its case from the investigator's tables, publishes
its ``disputes.opened`` and answers when the matching ``disputes.decided`` arrives, with the end-to-end latency
(publish to decided event, so the investigator, its tool calls and the outbox relay; not the reset). ``GET /summary``
gives the numbers of a run: throughput and latency from the shim, outbox lag from the database, database connections
sampled from pg_stat_activity, consumer lag from Kafka (the investigator group's committed offsets).

A test tool for the local stack only: it connects as the database owner, because it deletes cases.
"""

from __future__ import annotations

import asyncio
import json
import logging
import os
import time
from collections.abc import AsyncIterator, Iterable
from contextlib import asynccontextmanager
from pathlib import Path

import httpx
import psycopg
from aiokafka import AIOKafkaConsumer, AIOKafkaProducer, TopicPartition
from aiokafka.admin import AIOKafkaAdminClient
from fastapi import FastAPI, HTTPException
from psycopg_pool import AsyncConnectionPool

from shim.core import DisputePool, RunStats, Sent

log = logging.getLogger("shim")

# The disputes.opened event, built in PostgreSQL from the seeded intake tables like smoke.sh does.
EVENTS_SQL = """
SELECT d.dispute_id, json_build_object(
    'version', 1, 'dispute_id', d.dispute_id, 'cardholder_id', d.cardholder_id, 'card_id', d.card_id,
    'transaction_id', d.transaction_id, 'category', d.category,
    'opened_at', to_char(d.opened_at AT TIME ZONE 'UTC', 'YYYY-MM-DD"T"HH24:MI:SS"Z"'),
    'claimed_amount_minor', d.claimed_amount_minor, 'currency', d.currency,
    'cardholder_statement', d.cardholder_statement,
    'documents', COALESCE((SELECT json_agg(json_build_object('document_id', x.document_id, 'kind', x.kind,
        'issued_by', x.issued_by, 'dated', to_char(x.dated, 'YYYY-MM-DD'), 'summary', x.summary)
        ORDER BY x.document_id) FROM intake.dispute_documents x WHERE x.dispute_id = d.dispute_id), '[]'::json))::text
FROM intake.disputes d WHERE d.dispute_id = ANY(%s)
"""

# Connections per database role, sampled by the shim: a 5 s scrape misses connections busy for a few milliseconds.
CONNECTIONS_SQL = """
SELECT usename, count(*), count(*) FILTER (WHERE state = 'active')
FROM pg_stat_activity WHERE datname = 'disputes' AND usename IS NOT NULL AND pid <> pg_backend_pid()
GROUP BY usename
"""
SAMPLE_EVERY_S = 0.1
SAMPLE_LAG_EVERY_S = 0.5

# Prometheus queries over the run's window ({w} seconds, ending at the summary's end).
PROMQL = {
    "investigator_pool_size": 'sum(max_over_time(hikaricp_connections_max{{application="investigator"}}[{w}s]))',
    "outbox_unsent_max": "max_over_time(sum(dispute_outbox_unsent)[{w}s:5s])",
}


class Shim:
    def __init__(self) -> None:
        self.kafka = os.environ.get("KAFKA_BOOTSTRAP_SERVERS", "localhost:9092")
        self.database_url = os.environ.get("SHIM_DB_URL", "postgresql://disputes:disputes@localhost:5432/disputes")
        self.golden = Path(os.environ.get("GOLDEN_SET", "../../evals/golden/unauthorised-v1.json"))
        self.prometheus = os.environ.get("PROMETHEUS_URL", "http://localhost:9090")
        self.timeout_s = float(os.environ.get("DECIDED_TIMEOUT_SECONDS", "120"))
        self.stats = RunStats()
        self.events: dict[str, bytes] = {}
        self.waiting: dict[str, tuple[asyncio.Future[str], Sent]] = {}
        self.pool: DisputePool
        self.db: AsyncConnectionPool
        self.producer: AIOKafkaProducer
        self.consumer: AIOKafkaConsumer
        self._consuming: asyncio.Task[None]
        self._lock = asyncio.Lock()
        self._sampling: asyncio.Task[None]
        # (time, role, open, active), one row per role per sample.
        self.connections: list[tuple[float, str, int, int]] = []
        # (time, messages in disputes.opened not yet committed by the investigator's group).
        self.lag: list[tuple[float, int]] = []
        self._lagging: asyncio.Task[None]
        self.admin: AIOKafkaAdminClient
        self.group = os.environ.get("INVESTIGATOR_GROUP", "investigator")

    async def start(self) -> None:
        ids = [c["dispute_id"] for c in json.loads(self.golden.read_text(encoding="utf-8"))["cases"]]
        self.db = AsyncConnectionPool(self.database_url, min_size=1, max_size=8, open=False)
        await self.db.open(wait=True)
        async with self.db.connection() as conn:
            rows = await (await conn.execute(EVENTS_SQL, (ids,))).fetchall()
        self.events = {r[0]: r[1].encode() for r in rows}
        missing = sorted(set(ids) - self.events.keys())
        if missing:
            raise RuntimeError(f"golden disputes missing from intake.disputes (is the seed loaded?): {missing}")
        self.pool = DisputePool(ids)

        self.producer = AIOKafkaProducer(bootstrap_servers=self.kafka, acks="all")
        await self.producer.start()
        # No consumer group: every partition, from its end, so only this run's decisions are seen.
        self.consumer = AIOKafkaConsumer(bootstrap_servers=self.kafka, enable_auto_commit=False)
        await self.consumer.start()
        # The producer fetches the topic's metadata on demand; the consumer's cache knows assigned topics only.
        partitions = await self.producer.partitions_for("disputes.decided")
        tps = [TopicPartition("disputes.decided", p) for p in sorted(partitions)]
        self.consumer.assign(tps)
        await self.consumer.seek_to_end(*tps)
        for tp in tps:
            await self.consumer.position(tp)  # resolves the lazy seek before the first request
        self._consuming = asyncio.create_task(self._consume())
        self._sampling = asyncio.create_task(self._sample_connections())
        self.admin = AIOKafkaAdminClient(bootstrap_servers=self.kafka)
        await self.admin.start()
        opened = await self.producer.partitions_for("disputes.opened")
        self._lagging = asyncio.create_task(
            self._sample_lag([TopicPartition("disputes.opened", p) for p in sorted(opened)])
        )
        log.info("shim ready: %d disputes, %d decided partitions", len(ids), len(tps))

    async def stop(self) -> None:
        self._consuming.cancel()
        self._sampling.cancel()
        self._lagging.cancel()
        await self.admin.close()
        await self.consumer.stop()
        await self.producer.stop()
        await self.db.close()

    async def _consume(self) -> None:
        async for record in self.consumer:
            dispute_id = record.key.decode() if record.key else ""
            entry = self.waiting.pop(dispute_id, None)
            if entry is None:
                continue  # another run's, or a duplicate (delivery is at least once)
            future, sent = entry
            sent.latency_s = time.time() - sent.sent_at
            try:
                sent.state = json.loads(record.value).get("state")
            except ValueError:
                sent.state = "UNREADABLE"
            self.pool.release(dispute_id)
            if not future.done():
                future.set_result(sent.state or "")

    async def _sample_connections(self) -> None:
        # Its own connection, outside the pool, so the sampler never waits behind a reset.
        async with await psycopg.AsyncConnection.connect(self.database_url, autocommit=True) as conn:
            while True:
                now = time.time()
                for role, open_, active in await (await conn.execute(CONNECTIONS_SQL)).fetchall():
                    self.connections.append((now, role, open_, active))
                cutoff = now - 3600
                if self.connections and self.connections[0][0] < cutoff:
                    self.connections = [c for c in self.connections if c[0] >= cutoff]
                await asyncio.sleep(SAMPLE_EVERY_S)

    async def _sample_lag(self, tps: list[TopicPartition]) -> None:
        # Kafka's own numbers: the client's records-lag metric only exists while a partition is being fetched, so
        # Prometheus misses most of a short run.
        while True:
            try:
                ends = await self.consumer.end_offsets(tps)
                committed = await self.admin.list_consumer_group_offsets(self.group, partitions=tps)
                lag = sum(
                    ends[tp] - (committed[tp].offset if tp in committed and committed[tp].offset >= 0 else 0)
                    for tp in tps
                )
                now = time.time()
                self.lag.append((now, lag))
                if self.lag[0][0] < now - 3600:
                    self.lag = [x for x in self.lag if x[0] >= now - 3600]
            except Exception:  # a sampler must outlive a broker hiccup
                log.warning("consumer lag sample failed", exc_info=True)
            await asyncio.sleep(SAMPLE_LAG_EVERY_S)

    async def _reset(self, dispute_id: str) -> bool:
        """Deletes the dispute's case so the investigator handles it as new; ``False`` while its events are unsent."""
        async with self.db.connection() as conn, conn.transaction():
            unsent = await (
                await conn.execute(
                    "SELECT count(*) FROM investigation.outbox WHERE dispute_id = %s AND sent_at IS NULL", (dispute_id,)
                )
            ).fetchone()
            if unsent and unsent[0]:
                return False
            # The outbox keeps its rows: they are the run's outbox lag (summary).
            await conn.execute("DELETE FROM investigation.audit_events WHERE dispute_id = %s", (dispute_id,))
            await conn.execute("DELETE FROM investigation.cases WHERE dispute_id = %s", (dispute_id,))
        return True

    async def send(self) -> dict[str, object]:
        skipped: set[str] = set()
        while True:
            async with self._lock:
                dispute_id = self.pool.acquire(frozenset(skipped))
            if dispute_id is None:
                self.stats.rejected_at.append(time.time())
                raise HTTPException(503, f"all {self.pool.size} disputes are in flight (or have unsent events)")
            if await self._reset(dispute_id):
                break
            self.pool.release(dispute_id)
            skipped.add(dispute_id)

        future: asyncio.Future[str] = asyncio.get_running_loop().create_future()
        sent = Sent(dispute_id, time.time())
        self.waiting[dispute_id] = (future, sent)
        self.stats.sent.append(sent)
        await self.producer.send_and_wait("disputes.opened", self.events[dispute_id], key=dispute_id.encode())
        try:
            state = await asyncio.wait_for(asyncio.shield(future), self.timeout_s)
        except TimeoutError as e:
            # Stays in flight: if its decision comes later, it is counted then and the dispute freed.
            raise HTTPException(504, f"no disputes.decided for {dispute_id} within {self.timeout_s:.0f} s") from e
        return {"dispute_id": dispute_id, "state": state, "e2e_ms": round((sent.latency_s or 0) * 1000, 1)}

    async def summary(self, since: float, until: float | None) -> dict[str, object]:
        until = until or time.time()
        result = self.stats.summary(since)
        result["in_flight_now"] = self.pool.in_flight
        async with self.db.connection() as conn:
            row = await (
                await conn.execute(
                    """SELECT count(*),
                              percentile_cont(0.99) WITHIN GROUP (ORDER BY extract(epoch FROM sent_at - created_at)),
                              max(extract(epoch FROM sent_at - created_at)), count(*) FILTER (WHERE sent_at IS NULL)
                       FROM investigation.outbox WHERE created_at >= to_timestamp(%s)""",
                    (since,),
                )
            ).fetchone()
        result["outbox"] = {
            "events": row[0] if row else 0,
            "lag_p99_ms": None if not row or row[1] is None else round(float(row[1]) * 1000, 1),
            "lag_max_ms": None if not row or row[2] is None else round(float(row[2]) * 1000, 1),
            "unsent_now": row[3] if row else 0,
        }
        sampled = [c for c in self.connections if since <= c[0] <= until]
        result["db_connections"] = {
            "sample_every_ms": int(SAMPLE_EVERY_S * 1000),
            "open_max_by_role": _max_by_role((role, open_) for _, role, open_, _ in sampled),
            "active_max_by_role": _max_by_role((role, active) for _, role, _, active in sampled),
        }
        lags = [n for t, n in self.lag if since <= t <= until]
        result["consumer_lag"] = {
            "sample_every_ms": int(SAMPLE_LAG_EVERY_S * 1000),
            "max": max(lags) if lags else None,
            "at_end": lags[-1] if lags else None,
        }
        window = max(15, int(until - since) + 10)
        prom: dict[str, float | None] = {}
        async with httpx.AsyncClient(base_url=self.prometheus, timeout=10) as client:
            for name, query in PROMQL.items():
                try:
                    r = await client.get("/api/v1/query", params={"query": query.format(w=window), "time": until})
                    values = r.json()["data"]["result"]
                    prom[name] = float(values[0]["value"][1]) if values else None
                except (httpx.HTTPError, KeyError, ValueError):
                    prom[name] = None
        result["prometheus"] = prom
        return result


def _max_by_role(rows: Iterable[tuple[str, int]]) -> dict[str, int]:
    result: dict[str, int] = {}
    for role, n in rows:
        result[role] = max(n, result.get(role, 0))
    return dict(sorted(result.items()))


def create_app() -> FastAPI:
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(name)s %(message)s")
    shim = Shim()

    @asynccontextmanager
    async def lifespan(_app: FastAPI) -> AsyncIterator[None]:
        await shim.start()
        yield
        await shim.stop()

    app = FastAPI(title="load-shim", lifespan=lifespan)

    @app.get("/health")
    async def health() -> dict[str, object]:
        return {"status": "UP", "disputes": shim.pool.size, "in_flight": shim.pool.in_flight}

    @app.post("/disputes")
    async def send() -> dict[str, object]:
        return await shim.send()

    @app.get("/summary")
    async def summary(since: float, until: float | None = None) -> dict[str, object]:
        return await shim.summary(since, until)

    return app
