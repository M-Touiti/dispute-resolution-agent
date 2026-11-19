package dev.disputeagent.ledger.mcp;

import dev.disputeagent.ledger.mcp.ToolArguments.IdKind;
import dev.disputeagent.ledger.store.LedgerRecords.Authorisation;
import dev.disputeagent.ledger.store.LedgerRecords.Page;
import dev.disputeagent.ledger.store.LedgerRecords.Transaction;
import dev.disputeagent.ledger.store.LedgerRepository;
import dev.disputeagent.ledger.store.LedgerRepository.Filters;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.springframework.stereotype.Component;

/** The behaviour behind each ledger tool: validate the arguments, query, and return a JSON-ready result. */
@Component
class LedgerToolHandlers {

    private final LedgerRepository ledger;

    LedgerToolHandlers(LedgerRepository ledger) {
        this.ledger = ledger;
    }

    Object handle(LedgerTools tool, Map<String, Object> arguments) {
        var args = new ToolArguments(arguments);
        return switch (tool) {
            case GET_CARDHOLDER -> {
                String id = args.requiredId("cardholder_id", IdKind.CARDHOLDER);
                yield ledger.cardholder(id).orElseThrow(() -> ToolError.notFound("no cardholder " + id));
            }
            case GET_CARD -> {
                String id = args.requiredId("card_id", IdKind.CARD);
                yield ledger.card(id).orElseThrow(() -> ToolError.notFound("no card " + id));
            }
            case GET_MERCHANT -> {
                String id = args.requiredId("merchant_id", IdKind.MERCHANT);
                yield ledger.merchant(id).orElseThrow(() -> ToolError.notFound("no merchant " + id));
            }
            case GET_TRANSACTION -> {
                String id = args.requiredId("transaction_id", IdKind.TRANSACTION);
                yield ledger.transaction(id).orElseThrow(() -> ToolError.notFound("no transaction " + id));
            }
            case SEARCH_TRANSACTIONS -> searchTransactions(args);
            case GET_AUTHORISATIONS -> authorisations(args);
        };
    }

    private Page<Transaction> searchTransactions(ToolArguments args) {
        String card = existingCard(args);
        var filters = new Filters(args.optionalDate("from_date"), args.optionalDate("to_date"),
                args.optionalId("merchant_id", IdKind.MERCHANT), args.optionalEnum("kind"), null);
        ToolArguments.checkRange(filters.fromDate(), filters.toDate());
        int size = args.pageSize();
        String tool = LedgerTools.SEARCH_TRANSACTIONS.toolName();
        List<Transaction> rows = ledger.transactions(card, filters, args.cursor(tool), size + 1);
        return page(rows, size, tool, Transaction::transactionAt, Transaction::transactionId);
    }

    private Page<Authorisation> authorisations(ToolArguments args) {
        String card = existingCard(args);
        var filters = new Filters(args.optionalDate("from_date"), args.optionalDate("to_date"),
                args.optionalId("merchant_id", IdKind.MERCHANT), args.optionalEnum("response"),
                args.optionalId("auth_id", IdKind.AUTHORISATION));
        ToolArguments.checkRange(filters.fromDate(), filters.toDate());
        int size = args.pageSize();
        String tool = LedgerTools.GET_AUTHORISATIONS.toolName();
        List<Authorisation> rows = ledger.authorisations(card, filters, args.cursor(tool), size + 1);
        return page(rows, size, tool, Authorisation::requestedAt, Authorisation::authId);
    }

    private String existingCard(ToolArguments args) {
        String card = args.requiredId("card_id", IdKind.CARD);
        if (ledger.card(card).isEmpty()) {
            throw ToolError.notFound("no card " + card);
        }
        return card;
    }

    /** {@code rows} holds up to {@code size + 1} rows; the extra one only says whether another page exists. */
    private static <T> Page<T> page(List<T> rows, int size, String tool, Function<T, Instant> at,
            Function<T, String> id) {
        if (rows.size() <= size) {
            return new Page<>(rows, null);
        }
        List<T> items = rows.subList(0, size);
        T last = items.getLast();
        return new Page<>(List.copyOf(items), ToolArguments.pageToken(tool, at.apply(last), id.apply(last)));
    }
}
