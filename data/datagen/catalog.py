"""Word lists and merchant categories. Invented names only; tuples, never sets, so iteration order is fixed."""

from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class MerchantCategory:
    code: str
    ecommerce: bool
    recurring: bool
    min_minor: int
    max_minor: int
    nouns: tuple[str, ...]


GROCERY = "GROCERY"
RESTAURANT = "RESTAURANT"
FUEL = "FUEL"
PHARMACY = "PHARMACY"
ELECTRONICS_ONLINE = "ELECTRONICS_ONLINE"
FASHION_ONLINE = "FASHION_ONLINE"
TRAVEL = "TRAVEL"
STREAMING = "STREAMING"
FITNESS = "FITNESS"

CATEGORIES = (
    MerchantCategory(GROCERY, False, False, 500, 15_000, ("Market", "Grocers", "Pantry")),
    MerchantCategory(RESTAURANT, False, False, 1_500, 12_000, ("Bistro", "Kitchen", "Brasserie")),
    MerchantCategory(FUEL, False, False, 3_000, 12_000, ("Fuel", "Service Station", "Energy")),
    MerchantCategory(PHARMACY, False, False, 300, 6_000, ("Pharmacy", "Apothecary", "Chemist")),
    MerchantCategory(ELECTRONICS_ONLINE, True, False, 2_000, 150_000, ("Electronics", "Devices", "Gadgets")),
    MerchantCategory(FASHION_ONLINE, True, False, 1_500, 30_000, ("Outfitters", "Apparel", "Clothing")),
    MerchantCategory(TRAVEL, True, False, 5_000, 200_000, ("Travel", "Getaways", "Journeys")),
    MerchantCategory(STREAMING, True, True, 599, 2_499, ("Streaming", "Media", "Audio")),
    MerchantCategory(FITNESS, False, True, 1_999, 6_999, ("Fitness", "Gym", "Athletic Club")),
)

CATEGORY_BY_CODE = {c.code: c for c in CATEGORIES}

CITIES = (
    ("Lyon", "FR"),
    ("Bordeaux", "FR"),
    ("Lille", "FR"),
    ("Ghent", "BE"),
    ("Porto", "PT"),
    ("Valencia", "ES"),
    ("Bologna", "IT"),
    ("Graz", "AT"),
    ("Utrecht", "NL"),
    ("Leipzig", "DE"),
    ("Tampere", "FI"),
    ("Cork", "IE"),
)

GIVEN_NAMES = (
    "Amira",
    "Bastien",
    "Clara",
    "Dario",
    "Elin",
    "Farid",
    "Greta",
    "Hugo",
    "Ines",
    "Jonas",
    "Katja",
    "Luca",
    "Maelle",
    "Nils",
    "Olga",
    "Pavel",
    "Quentin",
    "Rosa",
    "Samir",
    "Tilda",
    "Ugo",
    "Vera",
    "Wim",
    "Yara",
    "Zeno",
    "Aline",
    "Bruno",
    "Celia",
    "Dmitri",
    "Esme",
)

FAMILY_NAMES = (
    "Almeida",
    "Berger",
    "Costa",
    "Dupont",
    "Eriksen",
    "Fontaine",
    "Gallo",
    "Hartmann",
    "Ivanova",
    "Janssen",
    "Kowalski",
    "Laurent",
    "Moreau",
    "Nieminen",
    "Ortega",
    "Peeters",
    "Quinn",
    "Rossi",
    "Schmid",
    "Torres",
    "Ulrich",
    "Vidal",
    "Weber",
    "Yilmaz",
    "Zimmer",
    "Bakker",
    "Carvalho",
    "Dumas",
    "Esposito",
    "Fischer",
)

MERCHANT_PREFIXES = (
    "Harbour",
    "Lumen",
    "Northgate",
    "Copperleaf",
    "Bramble",
    "Saltmarsh",
    "Kestrel",
    "Juniper",
    "Riverside",
    "Ashgrove",
    "Meadowlark",
    "Ironbridge",
    "Fernhill",
    "Stonecrest",
    "Larkspur",
    "Quayside",
    "Willowmere",
    "Brightwater",
    "Oakhollow",
    "Silverbirch",
)

CARD_PRODUCTS = ("CLASSIC", "GOLD", "PLATINUM", "BUSINESS")
