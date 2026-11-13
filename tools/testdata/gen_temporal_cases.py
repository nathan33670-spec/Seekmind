#!/usr/bin/env python3
"""Generates testdata/temporal_cases.tsv, the reference set of the time expressions
(docs/specs/recherche.md §3, ticket M5-02).

The expected intervals are computed here, independently of the Kotlin parser, from the rules of the
spec. Each line: query, reference instant, time zone, locale, expected start, expected end (both
inclusive, ISO 8601 with offset, or "-" when the query holds no time expression), label, text left once
the expression is removed. Run from the repository root:

    python tools/testdata/gen_temporal_cases.py
"""
import pathlib
from datetime import date, datetime, timedelta, timezone
from zoneinfo import ZoneInfo

OUT = pathlib.Path("testdata/temporal_cases.tsv")
MS = timedelta(milliseconds=1)

MOMENTS = {  # start hour, end hour, end on the next day
    "morning": (5, 12, False),
    "noon": (11, 14, False),
    "afternoon": (12, 18, False),
    "evening": (18, 2, True),
    "night": (22, 6, True),
}


class Ref:
    """A reference instant in a zone, and the interval builders of the spec."""

    def __init__(self, local, zone):
        self.zone = ZoneInfo(zone)
        self.now = datetime.fromisoformat(local).replace(tzinfo=self.zone)
        self.today = self.now.date()

    def start(self, d, hour=0):
        return datetime(d.year, d.month, d.day, hour, tzinfo=self.zone)

    def day(self, d):
        return self.start(d), self.start(d + timedelta(days=1))

    def days(self, first, last):
        return self.start(first), self.start(last + timedelta(days=1))

    def moment(self, d, name):
        begin, end, next_day = MOMENTS[name]
        return self.start(d, begin), self.start(d + timedelta(days=1 if next_day else 0), end)

    def until_now(self, begin):
        return begin, self.now + MS  # the end is the present instant, included

    def hours_back(self, hours):
        utc = self.now.astimezone(timezone.utc)
        return (utc - timedelta(hours=hours)).astimezone(self.zone), self.now + MS

    def weekday(self, iso_day, include_today=True):
        back = (self.today.isoweekday() - iso_day) % 7
        if back == 0 and not include_today:
            back = 7
        return self.today - timedelta(days=back)

    def monday(self):
        return self.today - timedelta(days=self.today.isoweekday() - 1)

    def last_saturday(self):
        return self.today - timedelta(days=(self.today.isoweekday() - 6) % 7)

    def month(self, year, month):
        nxt = date(year + (month == 12), month % 12 + 1, 1)
        return self.start(date(year, month, 1)), self.start(nxt)

    def recent_month(self, month):
        year = self.today.year if month <= self.today.month else self.today.year - 1
        return self.month(year, month)

    def recent_date(self, month, day):
        candidate = date(self.today.year, month, day)
        if candidate > self.today:
            candidate = date(self.today.year - 1, month, day)
        return self.day(candidate)

    def months_back(self, n):
        y, m = self.today.year, self.today.month - n
        while m <= 0:
            y, m = y - 1, m + 12
        last = (date(y + (m == 12), m % 12 + 1, 1) - timedelta(days=1)).day
        return date(y, m, min(self.today.day, last))


def fmt(dt):
    return dt.isoformat(timespec="milliseconds")


CASES = []


def case(query, local, zone, locale, label, remaining, rule):
    ref = Ref(local, zone)
    if rule is None:
        start = end = "-"
    else:
        begin, end_exclusive = rule(ref)
        last = (end_exclusive.astimezone(timezone.utc) - MS).astimezone(ref.zone)
        start, end = fmt(begin.astimezone(ref.zone)), fmt(last)
    CASES.append((query, fmt(ref.now), zone, locale, start, end, label, remaining))


PARIS = "Europe/Paris"
NY = "America/New_York"
FRI = "2026-11-13T15:30:00"  # a Friday
TUE = "2026-11-10T09:00:00"  # a Tuesday
SUN = "2026-11-15T20:00:00"  # a Sunday
NEW_YEAR = "2026-01-02T10:00:00"  # a Friday, just after the turn of the year


def fr(query, label, remaining, rule, now=FRI, zone=PARIS):
    case(query, now, zone, "fr-FR", label, remaining, rule)


def en(query, label, remaining, rule, now=FRI, zone=PARIS, locale="en-GB"):
    case(query, now, zone, locale, label, remaining, rule)


# Days.
fr("photos aujourd'hui", "aujourd'hui", "photos", lambda r: r.day(r.today))
fr("facture hier", "hier", "facture", lambda r: r.day(r.today - timedelta(days=1)))
fr("recette avant-hier", "avant-hier", "recette", lambda r: r.day(r.today - timedelta(days=2)))
fr("Hier", "Hier", "", lambda r: r.day(r.today - timedelta(days=1)))
en("pictures today", "today", "pictures", lambda r: r.day(r.today))
en("invoice yesterday", "yesterday", "invoice", lambda r: r.day(r.today - timedelta(days=1)))
en("recipe the day before yesterday", "the day before yesterday", "recipe", lambda r: r.day(r.today - timedelta(days=2)))

# Short windows.
fr("le lien de tout à l'heure", "tout à l'heure", "le lien de", lambda r: r.hours_back(6))
fr("message récemment", "récemment", "message", lambda r: r.hours_back(14 * 24))
fr("dernièrement article", "dernièrement", "article", lambda r: r.hours_back(14 * 24))
en("link earlier", "earlier", "link", lambda r: r.hours_back(6))
en("the link a moment ago", "a moment ago", "the link", lambda r: r.hours_back(6))
en("recently shared post", "recently", "shared post", lambda r: r.hours_back(14 * 24))
en("article lately", "lately", "article", lambda r: r.hours_back(14 * 24))

# Days of the week, today included; "dernier" / "last" excludes today.
FR_DAYS = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]
EN_DAYS = ["monday", "tuesday", "wednesday", "thursday", "friday", "saturday", "sunday"]
for iso, name in enumerate(FR_DAYS, start=1):
    fr(f"restaurant {name}", name, "restaurant", lambda r, d=iso: r.day(r.weekday(d)))
    fr(f"{name} dernier train", f"{name} dernier", "train", lambda r, d=iso: r.day(r.weekday(d, include_today=False)))
for iso, name in enumerate(EN_DAYS, start=1):
    en(f"restaurant on {name}", name, "restaurant", lambda r, d=iso: r.day(r.weekday(d)))
    en(f"train last {name}", f"last {name}", "train", lambda r, d=iso: r.day(r.weekday(d, include_today=False)))
# "mardi" said on a Tuesday is the current day.
fr("mardi réunion", "mardi", "réunion", lambda r: r.day(r.today), now=TUE)
fr("mardi dernier réunion", "mardi dernier", "réunion", lambda r: r.day(r.today - timedelta(days=7)), now=TUE)
en("meeting tuesday", "tuesday", "meeting", lambda r: r.day(r.today), now=TUE)
en("meeting last tuesday", "last tuesday", "meeting", lambda r: r.day(r.today - timedelta(days=7)), now=TUE)

# Weeks, weekends, months.
fr("cette semaine colis", "cette semaine", "colis", lambda r: r.until_now(r.start(r.monday())))
fr("la semaine dernière colis", "la semaine dernière", "colis",
   lambda r: r.days(r.monday() - timedelta(days=7), r.monday() - timedelta(days=1)))
fr("ce week-end concert", "ce week-end", "concert",
   lambda r: r.days(r.last_saturday(), r.last_saturday() + timedelta(days=1)))
fr("concert ce week-end", "ce week-end", "concert",
   lambda r: r.days(r.last_saturday(), r.last_saturday() + timedelta(days=1)), now=SUN)
fr("le week-end dernier concert", "le week-end dernier", "concert",
   lambda r: r.days(r.last_saturday() - timedelta(days=7), r.last_saturday() - timedelta(days=6)), now=SUN)
fr("le week-end dernier concert", "le week-end dernier", "concert",
   lambda r: r.days(r.last_saturday(), r.last_saturday() + timedelta(days=1)))
fr("ce mois-ci loyer", "ce mois-ci", "loyer", lambda r: r.until_now(r.start(r.today.replace(day=1))))
fr("loyer le mois dernier", "le mois dernier", "loyer",
   lambda r: r.month(*(r.today.year, r.today.month - 1) if r.today.month > 1 else (r.today.year - 1, 12)))
en("parcel this week", "this week", "parcel", lambda r: r.until_now(r.start(r.monday())))
en("parcel last week", "last week", "parcel",
   lambda r: r.days(r.monday() - timedelta(days=7), r.monday() - timedelta(days=1)))
en("gig this weekend", "this weekend", "gig",
   lambda r: r.days(r.last_saturday(), r.last_saturday() + timedelta(days=1)))
en("gig last weekend", "last weekend", "gig",
   lambda r: r.days(r.last_saturday() - timedelta(days=7), r.last_saturday() - timedelta(days=6)), now=SUN)
en("rent this month", "this month", "rent", lambda r: r.until_now(r.start(r.today.replace(day=1))))
en("rent last month", "last month", "rent", lambda r: r.month(r.today.year, r.today.month - 1))

# N days, weeks, months ago, in digits and in words.
FR_NUMBERS = ["un", "deux", "trois", "quatre", "cinq", "six", "sept", "huit", "neuf", "dix", "onze", "douze"]
EN_NUMBERS = ["one", "two", "three", "four", "five", "six", "seven", "eight", "nine", "ten", "eleven", "twelve"]
for n in (1, 3, 12):
    fr(f"vidéo il y a {FR_NUMBERS[n - 1]} jour{'s' if n > 1 else ''}", f"il y a {FR_NUMBERS[n - 1]} jour{'s' if n > 1 else ''}",
       "vidéo", lambda r, n=n: r.day(r.today - timedelta(days=n)))
    en(f"video {EN_NUMBERS[n - 1]} day{'s' if n > 1 else ''} ago", f"{EN_NUMBERS[n - 1]} day{'s' if n > 1 else ''} ago",
       "video", lambda r, n=n: r.day(r.today - timedelta(days=n)))
fr("il y a 5 jours vidéo", "il y a 5 jours", "vidéo", lambda r: r.day(r.today - timedelta(days=5)))
fr("il y a une semaine billet", "il y a une semaine", "billet",
   lambda r: r.days(r.today - timedelta(days=10), r.today - timedelta(days=4)))
fr("billet il y a 3 semaines", "il y a 3 semaines", "billet",
   lambda r: r.days(r.today - timedelta(days=24), r.today - timedelta(days=18)))
fr("billet il y a deux mois", "il y a deux mois", "billet",
   lambda r: r.days(r.months_back(2) - timedelta(days=15), r.months_back(2) + timedelta(days=14)))
fr("billet il y a 1 mois", "il y a 1 mois", "billet",
   lambda r: r.days(r.months_back(1) - timedelta(days=15), r.months_back(1) + timedelta(days=14)))
en("ticket a week ago", "a week ago", "ticket", lambda r: r.days(r.today - timedelta(days=10), r.today - timedelta(days=4)))
en("ticket 2 weeks ago", "2 weeks ago", "ticket", lambda r: r.days(r.today - timedelta(days=17), r.today - timedelta(days=11)))
en("ticket six months ago", "six months ago", "ticket",
   lambda r: r.days(r.months_back(6) - timedelta(days=15), r.months_back(6) + timedelta(days=14)))
en("ticket 1 day ago", "1 day ago", "ticket", lambda r: r.day(r.today - timedelta(days=1)))

# Months of the year: the most recent occurrence, the current month included.
FR_MONTHS = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre",
             "novembre", "décembre"]
EN_MONTHS = ["january", "february", "march", "april", "may", "june", "july", "august", "september", "october",
             "november", "december"]
for m in (1, 3, 8, 11, 12):
    fr(f"vacances en {FR_MONTHS[m - 1]}", f"en {FR_MONTHS[m - 1]}", "vacances", lambda r, m=m: r.recent_month(m))
    en(f"holidays in {EN_MONTHS[m - 1]}", f"in {EN_MONTHS[m - 1]}", "holidays", lambda r, m=m: r.recent_month(m))
fr("vacances en mars 2025", "en mars 2025", "vacances", lambda r: r.month(2025, 3))

# Dates: the most recent year when it is absent.
fr("rendez-vous le 12 mars", "12 mars", "rendez-vous", lambda r: r.recent_date(3, 12))
fr("rendez-vous 12 mars 2026", "12 mars 2026", "rendez-vous", lambda r: r.day(date(2026, 3, 12)))
fr("rendez-vous le 1er décembre", "1er décembre", "rendez-vous", lambda r: r.recent_date(12, 1))
fr("rendez-vous le 20 novembre", "20 novembre", "rendez-vous", lambda r: r.recent_date(11, 20))
fr("commande 12/03", "12/03", "commande", lambda r: r.recent_date(3, 12))
fr("commande 12/03/2026", "12/03/2026", "commande", lambda r: r.day(date(2026, 3, 12)))
fr("commande 2026-03-12", "2026-03-12", "commande", lambda r: r.day(date(2026, 3, 12)))
en("appointment march 12", "march 12", "appointment", lambda r: r.recent_date(3, 12))
en("appointment on 12 march 2026", "12 march 2026", "appointment", lambda r: r.day(date(2026, 3, 12)))
en("appointment march 12, 2026", "march 12, 2026", "appointment", lambda r: r.day(date(2026, 3, 12)))
en("appointment on march 3rd", "march 3rd", "appointment", lambda r: r.recent_date(3, 3))
en("order 12/03", "12/03", "order", lambda r: r.recent_date(3, 12), locale="en-GB")
en("order 03/12", "03/12", "order", lambda r: r.recent_date(3, 12), locale="en-US", zone=NY)
en("order 03/12/2026", "03/12/2026", "order", lambda r: r.day(date(2026, 3, 12)), locale="en-US", zone=NY)
en("order 2026-03-12", "2026-03-12", "order", lambda r: r.day(date(2026, 3, 12)), locale="en-US", zone=NY)

# Moments of the day, alone with "ce" / "this", or with a day.
for word, moment in [("matin", "morning"), ("midi", "noon"), ("soir", "evening")]:
    fr(f"ce {word} photo", f"ce {word}", "photo", lambda r, m=moment: r.moment(r.today, m))
    fr(f"hier {word} photo", f"hier {word}", "photo", lambda r, m=moment: r.moment(r.today - timedelta(days=1), m))
fr("cet après-midi photo", "cet après-midi", "photo", lambda r: r.moment(r.today, "afternoon"))
fr("photo mardi après-midi", "mardi après-midi", "photo", lambda r: r.moment(r.weekday(2), "afternoon"))
fr("photo mardi soir", "mardi soir", "photo", lambda r: r.moment(r.weekday(2), "evening"))
fr("photo lundi dernier au soir", "lundi dernier au soir", "photo",
   lambda r: r.moment(r.weekday(1, include_today=False), "evening"))
fr("photo hier dans la soirée", "hier dans la soirée", "photo", lambda r: r.moment(r.today - timedelta(days=1), "evening"))
fr("photo avant-hier matin", "avant-hier matin", "photo", lambda r: r.moment(r.today - timedelta(days=2), "morning"))
fr("photo le 12 mars au soir", "12 mars au soir", "photo", lambda r: (lambda d: r.moment(d, "evening"))(r.recent_date(3, 12)[0].date()))
fr("photo vendredi nuit", "vendredi nuit", "photo", lambda r: r.moment(r.today, "night"))
fr("photo il y a 3 jours le matin", "il y a 3 jours le matin", "photo", lambda r: r.moment(r.today - timedelta(days=3), "morning"))
# "cette nuit": the night that ended this morning before noon, the coming one after.
fr("cette nuit bruit", "cette nuit", "bruit", lambda r: r.moment(r.today - timedelta(days=1), "night"), now=TUE)
fr("cette nuit bruit", "cette nuit", "bruit", lambda r: r.moment(r.today, "night"))
en("this morning photo", "this morning", "photo", lambda r: r.moment(r.today, "morning"))
en("this afternoon photo", "this afternoon", "photo", lambda r: r.moment(r.today, "afternoon"))
en("photo this evening", "this evening", "photo", lambda r: r.moment(r.today, "evening"))
en("photo tonight", "tonight", "photo", lambda r: r.moment(r.today, "evening"))
en("photo last night", "last night", "photo", lambda r: r.moment(r.today - timedelta(days=1), "night"))
en("photo yesterday morning", "yesterday morning", "photo", lambda r: r.moment(r.today - timedelta(days=1), "morning"))
en("photo yesterday at noon", "yesterday at noon", "photo", lambda r: r.moment(r.today - timedelta(days=1), "noon"))
en("photo on tuesday evening", "tuesday evening", "photo", lambda r: r.moment(r.weekday(2), "evening"))
en("photo last monday night", "last monday night", "photo", lambda r: r.moment(r.weekday(1, include_today=False), "night"))
en("photo yesterday lunchtime", "yesterday lunchtime", "photo", lambda r: r.moment(r.today - timedelta(days=1), "noon"))
en("photo tuesday in the afternoon", "tuesday in the afternoon", "photo", lambda r: r.moment(r.weekday(2), "afternoon"))

# The evening runs over two days; changes of time; turn of the year.
fr("dîner samedi soir", "samedi soir", "dîner", lambda r: r.moment(r.weekday(6), "evening"), now="2026-10-26T09:00:00")
fr("dîner samedi soir", "samedi soir", "dîner", lambda r: r.moment(r.weekday(6), "evening"), now="2026-03-30T09:00:00")
fr("dimanche matin course", "dimanche matin", "course", lambda r: r.moment(r.weekday(7), "morning"), now="2026-03-30T09:00:00")
fr("dimanche course", "dimanche", "course", lambda r: r.day(r.weekday(7)), now="2026-03-30T09:00:00")
fr("dimanche course", "dimanche", "course", lambda r: r.day(r.weekday(7)), now="2026-10-26T09:00:00")
fr("hier nuit", "hier nuit", "", lambda r: r.moment(r.today - timedelta(days=1), "night"), now="2026-10-26T09:00:00")
en("dinner on saturday evening", "saturday evening", "dinner", lambda r: r.moment(r.weekday(6), "evening"),
   now="2026-11-02T09:00:00", zone=NY, locale="en-US")
en("run sunday morning", "sunday morning", "run", lambda r: r.moment(r.weekday(7), "morning"),
   now="2026-03-09T09:00:00", zone=NY, locale="en-US")
fr("loyer le mois dernier", "le mois dernier", "loyer", lambda r: r.month(2025, 12), now=NEW_YEAR)
fr("vacances en décembre", "en décembre", "vacances", lambda r: r.month(2025, 12), now=NEW_YEAR)
fr("réveillon le 31 décembre", "31 décembre", "réveillon", lambda r: r.day(date(2025, 12, 31)), now=NEW_YEAR)
fr("réveillon avant-hier soir", "avant-hier soir", "réveillon", lambda r: r.moment(date(2025, 12, 31), "evening"), now=NEW_YEAR)
fr("la semaine dernière colis", "la semaine dernière", "colis", lambda r: r.days(date(2025, 12, 22), date(2025, 12, 28)), now=NEW_YEAR)
fr("cette semaine colis", "cette semaine", "colis", lambda r: r.until_now(r.start(date(2025, 12, 29))), now=NEW_YEAR)
en("party last month", "last month", "party", lambda r: r.month(2025, 12), now=NEW_YEAR)
en("party in january", "in january", "party", lambda r: r.month(2026, 1), now=NEW_YEAR)
en("party on december 31", "december 31", "party", lambda r: r.day(date(2025, 12, 31)), now=NEW_YEAR)

# Linking words go with the expression.
fr("vu hier", "hier", "", lambda r: r.day(r.today - timedelta(days=1)))
fr("ce que j'ai vu hier soir", "hier soir", "ce que j'ai", lambda r: r.moment(r.today - timedelta(days=1), "evening"))
fr("restaurant vu le 12 mars", "12 mars", "restaurant", lambda r: r.recent_date(3, 12))
fr("article du mois dernier", "mois dernier", "article", lambda r: r.month(r.today.year, r.today.month - 1))
en("seen on tuesday", "tuesday", "", lambda r: r.day(r.weekday(2)))
en("post at noon yesterday", "yesterday", "post at noon", lambda r: r.day(r.today - timedelta(days=1)))
en("the article from last week", "last week", "the article",
   lambda r: r.days(r.monday() - timedelta(days=7), r.monday() - timedelta(days=1)))

# The longest expression wins.
fr("hier soir et lundi", "hier soir", "et lundi", lambda r: r.moment(r.today - timedelta(days=1), "evening"))
en("tuesday yesterday morning", "yesterday morning", "tuesday", lambda r: r.moment(r.today - timedelta(days=1), "morning"))

# No time expression: the query is left as it is.
for query, locale in [
    ("pâtes à la truffe", "fr-FR"), ("facture EDF", "fr-FR"), ("le soir de la fête", "fr-FR"),
    ("matin brun", "fr-FR"), ("weekend", "fr-FR"), ("12 euros", "fr-FR"), ("mars attaque", "fr-FR"),
    ("dernier métro", "fr-FR"), ("il y a des jours", "fr-FR"), ("31/02", "fr-FR"),
    ("the night manager", "en-GB"), ("may i help", "en-GB"), ("tonights show", "en-GB"),
    ("SUMMER20 code", "en-GB"), ("ago", "en-GB"), ("13/45", "en-GB"),
]:
    case(query, FRI, PARIS, locale, "-", query, None)

HEADER = "# Generated by tools/testdata/gen_temporal_cases.py; do not edit by hand.\n" \
    "# query\tnow\tzone\tlocale\tfrom\tto\tlabel\tremaining\n"

if __name__ == "__main__":
    OUT.write_text(HEADER + "".join("\t".join(row) + "\n" for row in CASES), encoding="utf-8")
    print(f"{len(CASES)} cases written to {OUT}")
