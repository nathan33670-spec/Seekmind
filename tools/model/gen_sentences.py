"""Generates the versioned source sentences of the tokenizer and embedding references.

    python tools/model/gen_sentences.py

Writes tools/model/data/tokenizer_sentences.txt (1,000 sentences, French and English, with punctuation,
numbers, emojis, URLs, codes and Unicode edge cases) and tools/model/data/embedding_texts.jsonl (50
queries and passages). Deterministic: same output on every run. Every value is invented: example.*
domains, made-up codes and amounts, common first names; no real personal data.
"""

from __future__ import annotations

import json
import random

import common

SEED = 20_261_112
SENTENCES = 1_000

FR = [
    "La réunion du comité est reportée à {date}, salle {room}.",
    "Votre commande {order} a été expédiée ; livraison prévue le {date}.",
    "Le code de vérification est {otp}. Ne le communiquez à personne.",
    "Recette : faire revenir les oignons, ajouter {n} g de pâtes et laisser mijoter {m} minutes.",
    "{name} a partagé un fichier : {file}",
    "Facture n° {invoice} — montant TTC : {price_fr}",
    "Rappel : rendez-vous chez le dentiste {day} à {time_fr}.",
    "Le train {train} à destination de Lyon partira voie {room} avec {m} minutes de retard.",
    "Mot de passe oublié ? Suivez ce lien : {url}",
    "« C'est l'été ! » s'exclama-t-elle, en ouvrant la fenêtre…",
    "Il fait {temp} °C à Ajaccio ; vent d'ouest à {n} km/h.",
    "L'équipe a corrigé le bogue {issue} dans la version {version}.",
    "Réservation confirmée : {n} nuits, check-in à partir de {time_fr}.",
    "Ça coûte {price_fr}, frais de port compris : c'est une bonne affaire {emoji}",
    "Où est passé le chargeur ? Je l'avais posé sur l'étagère.",
    "Les élèves de 3ᵉ B passeront l'épreuve écrite le {date}.",
    "Pensez à arroser les géraniums et à fermer le portail à clé.",
    "Votre colis est disponible au point relais ; code de retrait : {otp}.",
    "Écoute l'épisode {n} du balado « Histoires d'œuvres » {emoji}",
    "Taux d'intérêt : {pct} % sur {n} ans ; mensualité estimée {price_fr}.",
    "Nouvelle connexion détectée depuis {city} à {time_fr}.",
    "Merci pour ta réponse ! On se retrouve au café à {time_fr} ?",
    "Pièce jointe : {file} ({size} Ko)",
    "Les coordonnées GPS sont {lat}, {lon}.",
    "Mise à jour disponible : {version} — correctifs de sécurité et améliorations.",
    "Ticket {issue} : l'application plante au démarrage sur Android {android}.",
    "Prochaine échéance de l'abonnement : {date} ({price_fr}/mois).",
    "« Qu'est-ce qu'on mange ce soir ? » — « Des crêpes au sarrasin ! »",
    "Les soldes d'hiver commencent le {date} : jusqu'à −{pct} %.",
    "Le vol {flight} est à l'heure ; embarquement porte {gate}.",
    "Bœuf bourguignon, gratin dauphinois et crème brûlée au menu.",
    "Le numéro de suivi est {tracking}.",
    "Écrivez à {email} pour toute question sur votre dossier {invoice}.",
    "Température du processeur : {temp} °C ; ventilateur à {n} tr/min.",
    "Séance de sport : {n} répétitions × {m} séries, repos de {m} s.",
    "#{tag} Super soirée avec l'équipe {emoji}",
    "@{handle} tu as vu le dernier épisode ?",
    "Le fichier {file} a été modifié par {name} il y a {m} min.",
    "Total à payer : {price_fr}. Paiement accepté.",
    "Le musée est ouvert du mardi au dimanche, de 10 h à 18 h.",
]

EN = [
    "The quarterly review has been moved to {date_en} in room {room}.",
    "Your order {order} has shipped and should arrive by {date_en}.",
    "Your verification code is {otp}. Do not share it with anyone.",
    "Preheat the oven to {temp_f} °F and bake for {m} minutes.",
    "{name} shared a document with you: {file}",
    "Invoice #{invoice} — total due: {price_en}",
    "Reminder: dentist appointment on {day_en} at {time_en}.",
    "Train {train} to Boston is delayed by {m} minutes.",
    "Forgot your password? Reset it here: {url}",
    '"It\'s finally summer!" she said, opening the window...',
    "It's {temp} °C in Seattle; wind from the west at {n} mph.",
    "The team fixed bug {issue} in release {version}.",
    "Booking confirmed: {n} nights, check-in from {time_en}.",
    "Only {price_en} with free shipping — what a deal {emoji}",
    "Where did I leave the charger? I'm sure it was on the shelf.",
    "Don't forget to water the plants and lock the gate.",
    "Your parcel is ready for pickup; collection code: {otp}.",
    'Listen to episode {n} of the podcast "Stories of Things" {emoji}',
    "Interest rate: {pct}% over {n} years; estimated payment {price_en}.",
    "New sign-in detected from {city} at {time_en}.",
    "Thanks for the reply! Coffee at {time_en}?",
    "Attachment: {file} ({size} KB)",
    "GPS coordinates: {lat}, {lon}.",
    "Update available: {version} — security fixes and improvements.",
    "Issue {issue}: the app crashes on launch on Android {android}.",
    "Next billing date: {date_en} ({price_en}/month).",
    "Winter sale starts {date_en}: up to {pct}% off!",
    "Flight {flight} is on time; boarding at gate {gate}.",
    "Your tracking number is {tracking}.",
    "Email {email} with any questions about case {invoice}.",
    "CPU temperature: {temp} °C; fan at {n} RPM.",
    "Workout: {n} reps × {m} sets, {m}s rest.",
    "#{tag} Great night out with the team {emoji}",
    "@{handle} did you watch the latest episode?",
    "{file} was edited by {name} {m} min ago.",
    "The museum is open Tuesday to Sunday, 10 a.m. to 6 p.m.",
    "Total: {price_en}. Payment approved.",
    "I can't believe it's already {date_en}!",
    "Meeting notes: action items for Q{q}, owners TBD.",
    "Run `./gradlew assembleRelease` then upload the APK.",
]

# Unicode edge cases for the normalisation of the tokenizer (docs/specs/embeddings.md §3.1).
EDGE = [
    "Espace insécable avant le point-virgule\u00a0; et fine avant le point d'exclamation\u202f!",
    "Tab\tseparated\tvalues\tand  double  spaces",
    "Zero\u200bwidth\u200bspace and soft\u00adhyphen",
    "Ligatures : ﬁchier, ﬂeur, œuvre, Æsop",
    "Exposants et fractions : m², 10³, ½, ¾, nº 5",
    "Pleine chasse : ＳｅｅｋＭｉｎｄ １２３",
    "Combining accents: cafe\u0301, nai\u0308ve, Ångstro\u0308m",
    "Emoji family 👨‍👩‍👧‍👦, flag 🇫🇷, skin tone 👍🏽, heart ❤️",
    "Ελληνικά, русский текст, العربية, 日本語のテキスト, 한국어",
    "ALL CAPS SENTENCE WITH NUMBERS 2026 AND SYMBOLS #@$%",
    "Math: x² + y² = z², ∑ i = n(n+1)/2, π ≈ 3.14159",
    "Quotes: “curly”, ‘single’, «french», „german“",
    "Dashes – en — em ‒ figure, ellipsis … and bullet •",
    "Currency: 12,50 € · $1,299.99 · £7 · ¥1200 · 0,99 CHF",
    "Path C:\\Users\\Public\\Documents\\rapport.docx",
    'JSON {"key": [1, 2, 3], "ok": true}',
    "SQL SELECT * FROM capture WHERE id = 42;",
    "Kotlin: val x = listOf(1, 2).map { it * 2 }",
    "Hash 3f9a1c0b7e2d4f6a8b9c0d1e2f3a4b5c",
    "IPv4 192.0.2.17 and IPv6 2001:db8::1",
    "   leading and trailing spaces   ",
    "a",
    "?!",
    "🙂",
    "1234567890",
]

EMBEDDING_TEXTS = [
    ("query", "horaires du musée"),
    ("query", "code de retrait du colis"),
    ("query", "recette de pâtes à la truffe"),
    ("query", "facture d'électricité de novembre"),
    ("query", "retard du train pour Lyon"),
    ("query", "mot de passe oublié"),
    ("query", "rendez-vous chez le dentiste"),
    ("query", "museum opening hours"),
    ("query", "parcel pickup code"),
    ("query", "flight boarding gate"),
    ("query", "réservation d'hôtel"),
    ("query", "nouvelle connexion suspecte"),
    ("query", "taux du prêt immobilier"),
    ("query", "app crash on launch"),
    ("query", "météo à Ajaccio"),
    ("query", "CMD-2026-48213"),
    ("query", "soldes d'hiver"),
    ("query", "podcast episode"),
    ("query", "liste de courses"),
    ("query", "workout plan"),
    ("passage", "Le musée est ouvert du mardi au dimanche, de 10 h à 18 h. Fermé le lundi et les jours fériés."),
    ("passage", "Votre colis est disponible au point relais Tabac de la Gare. Code de retrait : 482913. Il sera conservé 10 jours."),
    ("passage", "Recette : faire cuire 300 g de pâtes fraîches, ajouter de la crème, du parmesan et quelques lamelles de truffe noire."),
    ("passage", "Facture d'électricité — période du 1er au 30 novembre — montant TTC : 84,27 €. Prélèvement le 15 décembre."),
    ("passage", "Le train 6123 à destination de Lyon Part-Dieu partira voie 7 avec 25 minutes de retard."),
    ("passage", "Mot de passe oublié ? Suivez ce lien pour le réinitialiser : https://compte.example.fr/reinitialiser"),
    ("passage", "Rappel : rendez-vous chez le dentiste jeudi à 14 h 30, cabinet du Dr Martin, 2e étage."),
    ("passage", "The museum is open Tuesday to Sunday, 10 a.m. to 6 p.m. Closed on Mondays and public holidays."),
    ("passage", "Your parcel is ready for pickup at the corner store. Collection code: 731905. It will be kept for 7 days."),
    ("passage", "Flight AF1742 is on time. Boarding starts at 9:40 at gate K32. Please have your passport ready."),
    ("passage", "Réservation confirmée à l'Hôtel des Îles : 3 nuits, arrivée à partir de 15 h, petit-déjeuner inclus."),
    (
        "passage",
        "Nouvelle connexion à votre compte détectée depuis Marseille à 23 h 12. Si ce n'était pas vous, changez votre mot de passe.",
    ),
    ("passage", "Taux d'intérêt fixe : 3,4 % sur 20 ans ; mensualité estimée 1 152 € hors assurance."),
    ("passage", "Issue 1842: the app crashes on launch on Android 14 when the notification permission is denied."),
    ("passage", "Ajaccio : ensoleillé, 19 °C l'après-midi, vent d'ouest à 20 km/h, mer peu agitée."),
    ("passage", "Commande CMD-2026-48213 expédiée par transporteur. Livraison prévue mardi entre 9 h et 13 h."),
    ("passage", "Les soldes d'hiver commencent le 7 janvier : jusqu'à −50 % sur les manteaux et les chaussures."),
    ("passage", "Listen to episode 12 of the podcast Stories of Things: the history of the paperclip."),
    ("passage", "Liste de courses : lait, œufs, farine, beurre, pommes, café moulu, liquide vaisselle."),
    ("passage", "Workout plan: 4 sets of 10 squats, 3 sets of 12 push-ups, 1 minute plank, 60 seconds rest."),
    ("passage", "La réunion du comité de quartier est reportée au 3 décembre à 18 h 30, salle polyvalente."),
    ("passage", "Your verification code is 590214. It expires in 10 minutes. Do not share it with anyone."),
    ("passage", "Bœuf bourguignon, gratin dauphinois et crème brûlée : le menu du dimanche est prêt."),
    ("passage", "Mise à jour 2.14.3 disponible : correctifs de sécurité et amélioration de l'autonomie."),
    ("passage", "Pièce jointe : rapport_annuel_2026.pdf (2,4 Mo), envoyée par Camille."),
    ("passage", "Thanks for the reply! Let's meet at the café on Rue Neuve at 8:30 tomorrow."),
    ("passage", "Il fait 31 °C à l'ombre : pensez à arroser les tomates et les géraniums ce soir."),
    ("passage", "Abonnement musique : prochaine échéance le 2 janvier (10,99 €/mois)."),
    ("passage", "Le vol est annulé en raison d'une grève ; un remboursement intégral vous sera proposé."),
    ("passage", "Paiement refusé : votre carte a expiré. Mettez à jour vos informations de paiement."),
]

NAMES = ["Camille", "Alex", "Sacha", "Jordan", "Léa", "Hugo", "Inès", "Noah", "Emma", "Lucas", "Chloé", "Sam"]
CITIES = ["Ajaccio", "Bastia", "Lyon", "Nantes", "Lille", "Genève", "Bruxelles", "Montréal", "Boston", "Seattle"]
DAYS_FR = ["lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche"]
DAYS_EN = ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"]
MONTHS_FR = ["janvier", "février", "mars", "avril", "mai", "juin", "juillet", "août", "septembre", "octobre", "novembre", "décembre"]
MONTHS_EN = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"]
EMOJIS = ["😀", "🎉", "👍", "🙏", "🔥", "✅", "❤️", "😂", "🚆", "📦", "☀️", "🍝", "👍🏽", "🇫🇷"]
TAGS = ["vacances", "Corse", "dev", "AndroidDev", "rentrée", "food", "weekend", "teamwork"]
FILES = ["rapport_final_v3.pdf", "photos_été.zip", "budget-2026.xlsx", "notes réunion.docx", "IMG_4821.jpg", "build.log"]
DOMAINS = ["example.com", "example.org", "example.fr", "exemple.test"]


def slots(rng: random.Random) -> dict[str, str]:
    day = rng.randint(1, 28)
    month = rng.randrange(12)
    return {
        "date": f"{day} {MONTHS_FR[month]}" if rng.random() < 0.5 else f"{day:02d}/{month + 1:02d}/2026",
        "date_en": f"{MONTHS_EN[month]} {day}" if rng.random() < 0.5 else f"{month + 1}/{day}/2026",
        "day": DAYS_FR[rng.randrange(7)],
        "day_en": DAYS_EN[rng.randrange(7)],
        "time_fr": f"{rng.randint(7, 22)} h {rng.choice(['00', '15', '30', '45'])}"
        if rng.random() < 0.5
        else f"{rng.randint(7, 22)}h{rng.choice(['00', '15', '30', '45'])}",
        "time_en": f"{rng.randint(1, 12)}:{rng.choice(['00', '15', '30', '45'])} {rng.choice(['a.m.', 'p.m.', 'AM', 'PM'])}",
        "room": str(rng.randint(1, 24)),
        "order": f"CMD-2026-{rng.randint(10_000, 99_999)}",
        "invoice": f"F{rng.randint(2025, 2026)}-{rng.randint(1, 9999):04d}",
        "otp": f"{rng.randint(0, 999_999):06d}",
        "n": str(rng.randint(2, 500)),
        "m": str(rng.randint(2, 59)),
        "q": str(rng.randint(1, 4)),
        "pct": rng.choice(["3,4", "20", "50", "1.9", "70", "12.5"]),
        "temp": str(rng.randint(-5, 42)),
        "temp_f": str(rng.choice([350, 375, 400, 425])),
        "name": rng.choice(NAMES),
        "city": rng.choice(CITIES),
        "file": rng.choice(FILES),
        "size": str(rng.randint(12, 9_999)),
        "price_fr": f"{rng.randint(1, 2_000)},{rng.randint(0, 99):02d} €",
        "price_en": f"${rng.randint(1, 2_000):,}.{rng.randint(0, 99):02d}",
        "url": f"https://{rng.choice(['www.', 'compte.', ''])}{rng.choice(DOMAINS)}/{rng.choice(['reset', 'aide/faq', 'r/8f3K2x', 'compte?id=42&lang=fr'])}",
        "email": f"{rng.choice(['contact', 'support', 'hello', 'service.client'])}@{rng.choice(DOMAINS)}",
        "issue": f"#{rng.randint(100, 9_999)}",
        "version": f"v{rng.randint(1, 4)}.{rng.randint(0, 20)}.{rng.randint(0, 9)}",
        "android": str(rng.randint(11, 16)),
        "train": str(rng.randint(1000, 9999)),
        "flight": f"{rng.choice(['AF', 'XK', 'TO', 'U2'])}{rng.randint(100, 9999)}",
        "gate": f"{rng.choice('ABCDK')}{rng.randint(1, 40)}",
        "tracking": f"{rng.choice(['6A', '1Z', 'LP'])}{rng.randint(10**10, 10**11 - 1)}FR",
        "lat": f"{rng.uniform(41, 51):.5f}",
        "lon": f"{rng.uniform(-4, 9):.5f}",
        "emoji": rng.choice(EMOJIS),
        "tag": rng.choice(TAGS),
        "handle": rng.choice(["camille_dev", "alex.b", "sacha42", "team_corse"]),
    }


def sentences() -> list[str]:
    rng = random.Random(SEED)
    result = list(EDGE)
    while len(result) < SENTENCES:
        template = rng.choice(FR if rng.random() < 0.55 else EN)
        sentence = template.format(**slots(rng))
        if rng.random() < 0.15:  # a second sentence, as on a real screen
            sentence += " " + rng.choice(FR + EN).format(**slots(rng))
        if sentence not in result:
            result.append(sentence)
    return result


def main() -> None:
    lines = sentences()
    if any("\n" in line or "\r" in line for line in lines):
        raise SystemExit("a sentence contains a line break")
    target = common.DATA / "tokenizer_sentences.txt"
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text("\n".join(lines) + "\n", encoding="utf-8")
    texts = common.DATA / "embedding_texts.jsonl"
    texts.write_text(
        "".join(json.dumps({"kind": kind, "text": text}, ensure_ascii=False) + "\n" for kind, text in EMBEDDING_TEXTS),
        encoding="utf-8",
    )
    print(f"{len(lines)} sentences -> {target.relative_to(common.REPO)}")
    print(f"{len(EMBEDDING_TEXTS)} texts -> {texts.relative_to(common.REPO)}")


if __name__ == "__main__":
    main()
