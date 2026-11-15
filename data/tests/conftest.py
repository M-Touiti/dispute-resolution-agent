import pytest

from datagen.generator import Config, Dataset, generate
from datagen.writers import write_dataset

SEED = 20261101


@pytest.fixture(scope="session")
def config() -> Config:
    return Config(seed=SEED)


@pytest.fixture(scope="session")
def dataset(config: Config) -> Dataset:
    return generate(config)


@pytest.fixture(scope="session")
def written(dataset, config, tmp_path_factory):
    out = tmp_path_factory.mktemp("generated")
    manifest = write_dataset(dataset, config, out)
    return out, manifest
