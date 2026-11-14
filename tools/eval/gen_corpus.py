"""Generates the reference set of the search evaluation (docs/specs/recherche.md §11, ticket M5-07).

    python tools/eval/gen_corpus.py

Writes testdata/eval/corpus.jsonl (about 300 synthetic captures: two thirds French, one third English,
fifteen applications, sixty days) and testdata/eval/queries.jsonl (about 80 queries annotated by type and
language, each with the keys of the captures it must find). Deterministic: same output on every run.

The corpus has two parts:
- the anchors, written by hand below, which the queries look for;
- the fillers, built from templates, which make the search harder without answering any query.

Every value is invented: common first names, example.* domains, made-up codes, amounts and addresses;
no real personal data. Dates are relative to NOW (a Tuesday evening, Europe/Paris), so that temporal
queries ("hier", "mardi soir") have a single right answer.
"""

from __future__ import annotations

import json
import random
from datetime import datetime, timedelta
from pathlib import Path
from zoneinfo import ZoneInfo

SEED = 20_261_114
ZONE = ZoneInfo("Europe/Paris")
# Tuesday 10 November 2026, 21:00 in Paris.
NOW = datetime(2026, 11, 10, 21, 0, tzinfo=ZONE)
DAYS = 60
FILLERS = 240

ROOT = Path(__file__).resolve().parents[2]
OUT = ROOT / "testdata" / "eval"

APPS = {
    "chrome": ("com.android.chrome", "Chrome"),
    "instagram": ("com.instagram.android", "Instagram"),
    "whatsapp": ("com.whatsapp", "WhatsApp"),
    "gmail": ("com.google.android.gm", "Gmail"),
    "keep": ("com.google.android.keep", "Keep"),
    "maps": ("com.google.android.apps.maps", "Maps"),
    "youtube": ("com.google.android.youtube", "YouTube"),
    "spotify": ("com.spotify.music", "Spotify"),
    "amazon": ("com.amazon.mShop.android.shopping", "Amazon"),
    "sncf": ("com.sncf.ouisncf", "SNCF Connect"),
    "leboncoin": ("fr.leboncoin", "leboncoin"),
    "reddit": ("com.reddit.frontpage", "Reddit"),
    "x": ("com.twitter.android", "X"),
    "agenda": ("com.google.android.calendar", "Agenda"),
    "linkedin": ("com.linkedin.android", "LinkedIn"),
}

# (key, language, app, days ago, hour, window title, text, url)
ANCHORS = [
    # Recipes and food
    ("recipe-tatin", "fr", "chrome", 3, 19, "Tarte tatin facile",
     "Tarte tatin facile\nPréparation : 20 min, cuisson : 35 min\nFaire caraméliser 120 g de sucre avec 50 g de "
     "beurre, ranger les quartiers de pommes, couvrir de pâte brisée et enfourner à 200 °C. Démouler encore tiède.",
     "https://cuisine.example.fr/tarte-tatin"),
    ("recipe-risotto", "fr", "youtube", 12, 20, "Risotto aux champignons — la méthode du chef",
     "Risotto aux champignons — la méthode du chef\nNacrer le riz arborio, mouiller au bouillon louche après louche, "
     "ajouter les cèpes poêlés, finir avec parmesan et une noix de beurre hors du feu.", None),
    ("recipe-curry", "en", "chrome", 25, 18, "Easy chickpea curry",
     "Easy chickpea curry\nFry onion, garlic and ginger, add curry paste, two cans of chickpeas and coconut milk. "
     "Simmer for 15 minutes and serve with basmati rice and fresh coriander.", "https://food.example.com/chickpea-curry"),
    ("recipe-crepes", "fr", "keep", 40, 10, "Pâte à crêpes",
     "Pâte à crêpes\n250 g de farine, 4 œufs, 50 cl de lait, 1 pincée de sel, 2 cuillères d'huile. "
     "Laisser reposer une heure avant la cuisson.", None),
    ("restaurant-zinc", "fr", "maps", 6, 13, "Le Petit Zinc",
     "Le Petit Zinc\nBistrot · 4,5 ★ (812 avis)\n18 rue des Tanneurs\nOuvert jusqu'à 23:00\n"
     "Spécialités : blanquette de veau, île flottante. Réservation conseillée le week-end.", None),
    ("restaurant-sushi", "en", "maps", 19, 12, "Kumo Sushi Bar",
     "Kumo Sushi Bar\nJapanese · 4.7 ★ (1,203 reviews)\n42 Harbour Street\nOpen until 22:30\n"
     "Popular dishes: salmon nigiri, spicy tuna roll, matcha cheesecake.", None),
    # Travel
    ("train-lyon", "fr", "sncf", 2, 8, "Votre billet",
     "Paris Gare de Lyon → Lyon Part-Dieu\nTGV INOUI 6617 · départ 07:12 · arrivée 09:08\nVoiture 14, place 52\n"
     "Référence de dossier : QXH7TP", None),
    ("flight-lisbon", "fr", "gmail", 9, 22, "Confirmation de votre vol",
     "Confirmation de votre vol\nParis-Orly (ORY) → Lisbonne (LIS) · vol TP 4421\nSamedi 21 novembre, départ 10:35\n"
     "Code de réservation : K7M2QZ\nBagage cabin 10 kg inclus.", None),
    ("hotel-bruges", "en", "gmail", 31, 21, "Your booking is confirmed",
     "Your booking is confirmed\nCanal View Hotel, Bruges\nCheck-in Friday 4 December from 15:00, check-out Sunday 11:00\n"
     "Confirmation number 5521-889-034\nFree cancellation until 2 December.", None),
    ("rental-car", "fr", "gmail", 15, 9, "Location de voiture",
     "Location de voiture — Agence Aéroport\nRetrait le 21 novembre à 12:30, retour le 24 novembre\n"
     "Catégorie compacte, boîte manuelle\nNuméro de contrat LV-30419", None),
    ("map-hike", "fr", "maps", 22, 7, "Sentier du Monte Cinto",
     "Sentier du Monte Cinto\nRandonnée difficile · 14,2 km · dénivelé 1 750 m\nDépart du refuge de l'Ercu, "
     "prévoir de l'eau et partir tôt : orages fréquents l'après-midi.", None),
    # Shopping and orders
    ("order-headphones", "fr", "amazon", 4, 18, "Détails de la commande",
     "Commande n° 402-7781934-5521063\nCasque sans fil à réduction de bruit — noir\nLivraison prévue jeudi 12 novembre\n"
     "Total : 89,99 €", None),
    ("order-books", "en", "amazon", 27, 20, "Order details",
     "Order #113-5528740-9912646\n2 items: 'The Lighthouse Keeper' paperback, desk lamp with USB charging\n"
     "Delivered on 16 October · Total £34.50", None),
    ("parcel-relay", "fr", "gmail", 1, 11, "Votre colis vous attend",
     "Votre colis vous attend au point relais Tabac de la Gare\nCode de retrait : 482913\n"
     "Numéro de suivi 6A19284755031\nÀ retirer avant le 18 novembre.", None),
    ("bike-sale", "fr", "leboncoin", 7, 21, "Vélo de ville",
     "Vélo de ville femme 28 pouces\n180 €\nTrès bon état, 7 vitesses, panier avant et antivol fournis.\n"
     "Remise en main propre uniquement.", None),
    ("sofa-sale", "en", "leboncoin", 33, 16, "Grey corner sofa",
     "Grey corner sofa, 3 seats\n£250\nRemovable washable covers, pet-free home.\nBuyer collects.", None),
    ("promo-code", "fr", "gmail", 5, 9, "-25 % ce week-end",
     "Ce week-end seulement : -25 % sur toute la collection hiver avec le code HIVER25.\n"
     "Offre valable jusqu'au dimanche minuit.", None),
    ("invoice-power", "fr", "gmail", 18, 8, "Votre facture d'électricité",
     "Votre facture d'électricité est disponible\nFacture n° FE-2026-104558\nMontant : 74,12 € · prélèvement le 25 novembre\n"
     "Consommation : 312 kWh.", None),
    # Messages
    ("msg-dinner-tuesday", "fr", "whatsapp", 0, 19, "Camille",
     "Camille : On se retrouve à 20 h devant le cinéma ? Je réserve les places pour le film de science-fiction.\n"
     "Moi : Parfait, j'arrive en métro.", None),
    ("msg-keys", "fr", "whatsapp", 1, 8, "Julien",
     "Julien : J'ai laissé les clés de l'appartement sous le paillasson, la boîte aux lettres est cassée.", None),
    ("msg-birthday", "fr", "whatsapp", 14, 18, "Famille",
     "Maman : N'oubliez pas l'anniversaire de mamie dimanche, on apporte le gâteau au chocolat.\n"
     "Léa : Je m'occupe des bougies !", None),
    ("msg-moving", "en", "whatsapp", 20, 17, "Tom",
     "Tom: Can you help me move on Saturday? I rented a van from 9 am, pizza and drinks on me.", None),
    ("msg-wifi", "fr", "whatsapp", 37, 21, "Colocation",
     "Sarah : Le nouveau mot de passe du wifi est sur le frigo, la box a été changée ce matin.", None),
    ("msg-babysitter", "en", "whatsapp", 8, 18, "Emma",
     "Emma: The babysitter can come Friday from 7 pm until midnight, she charges 12 per hour.", None),
    # Notes and tasks
    ("note-groceries", "fr", "keep", 0, 12, "Courses",
     "Courses\n- lait d'avoine\n- tomates cerises\n- pain complet\n- café en grains\n- lessive", None),
    ("note-gift-ideas", "fr", "keep", 26, 22, "Idées cadeaux",
     "Idées cadeaux Noël\nPapa : livre sur l'histoire de la Corse\nInès : kit d'aquarelle\nThéo : jeu de société coopératif", None),
    ("note-meeting", "en", "keep", 10, 15, "Project kickoff",
     "Project kickoff notes\n- launch date moved to March\n- Alice owns the budget review\n- next sync Thursday 10:00", None),
    ("note-car", "fr", "keep", 45, 18, "Voiture",
     "Voiture : contrôle technique avant le 15 janvier, vidange à 60 000 km, pression des pneus 2,3 bars.", None),
    ("note-wifi-code", "en", "keep", 52, 20, "Guest wifi",
     "Guest wifi network: Maison-Invites · password: lemon-tree-42", None),
    # Agenda
    ("event-dentist", "fr", "agenda", 11, 9, "Dentiste",
     "Dentiste — Dr Morel\nMercredi 18 novembre · 16:30 – 17:00\nCabinet du 5 place de la République", None),
    ("event-yoga", "en", "agenda", 16, 7, "Yoga class",
     "Yoga class\nEvery Monday · 18:30 – 19:30\nStudio Lotus, second floor\nBring your own mat", None),
    ("event-parents", "fr", "agenda", 3, 20, "Réunion parents-professeurs",
     "Réunion parents-professeurs\nJeudi 19 novembre · 17:45\nCollège Victor-Hugo, salle 204", None),
    # Articles and videos
    ("article-lighthouses", "fr", "chrome", 13, 21, "Les phares de la côte corse",
     "Les phares de la côte corse\nDe la Giraglia aux îles Sanguinaires, les gardiens se relayaient chaque nuit pour "
     "allumer la lanterne. Aujourd'hui automatisés, plusieurs phares se visitent l'été.",
     "https://histoire.example.fr/phares-corse"),
    ("article-sleep", "en", "chrome", 6, 23, "Why we sleep less in winter",
     "Why we sleep less in winter\nShorter days shift the release of melatonin; researchers recommend morning "
     "daylight exposure and a fixed bedtime to keep a regular rhythm.", "https://science.example.com/winter-sleep"),
    ("article-heat-pump", "fr", "chrome", 29, 14, "Pompe à chaleur : faut-il se lancer ?",
     "Pompe à chaleur : faut-il se lancer ?\nUne pompe air-eau consomme trois fois moins qu'un radiateur électrique, "
     "mais l'installation coûte entre 10 000 et 16 000 euros avant les aides.",
     "https://maison.example.fr/pompe-a-chaleur"),
    ("article-ev-battery", "en", "reddit", 17, 22, "r/electricvehicles",
     "How long do EV batteries really last? After 200,000 km my car still has 91% of its original capacity. "
     "Slow charging at home and avoiding 100% every night seem to help.", None),
    ("article-bread", "fr", "reddit", 35, 11, "r/boulangerie",
     "Mon levain est enfin actif : rafraîchi deux fois par jour pendant une semaine, il double en six heures. "
     "Premier pain de campagne ce week-end !", None),
    ("video-guitar", "en", "youtube", 21, 21, "Learn 4 chords, play 100 songs",
     "Learn 4 chords, play 100 songs\nG, D, E minor and C: practise the changes slowly, then add a simple strumming "
     "pattern down, down-up, up-down-up.", None),
    ("video-plumbing", "fr", "youtube", 9, 19, "Réparer une fuite sous l'évier",
     "Réparer une fuite sous l'évier\nCouper l'eau, dévisser le siphon, changer le joint et remettre du téflon sur le "
     "filetage avant de resserrer.", None),
    # Social
    ("insta-beach", "fr", "instagram", 5, 18, "Instagram",
     "lucie.voyage · Coucher de soleil sur la plage de Palombaggia 🌅 L'eau était encore à 22 degrés !\n"
     "J'aime 1 204 · 38 commentaires", None),
    ("insta-cake", "en", "instagram", 23, 16, "Instagram",
     "bake.with.nina · Three-layer lemon cake with blueberry frosting for my sister's wedding 🍋\n"
     "2,310 likes · 112 comments", None),
    ("insta-concert", "fr", "instagram", 2, 23, "Instagram",
     "marco.live · Concert incroyable hier soir au Zénith, la première partie était géniale 🎸\n"
     "J'aime 532 · 17 commentaires", None),
    ("x-strike", "fr", "x", 1, 7, "X",
     "Info trafic : grève sur la ligne B du RER demain, un train sur deux aux heures de pointe. "
     "Prévoyez des trajets alternatifs.", None),
    ("x-launch", "en", "x", 30, 18, "X",
     "The new space telescope captured its first image of a distant galaxy cluster today — "
     "thousands of galaxies in a single frame.", None),
    ("linkedin-job", "fr", "linkedin", 12, 12, "Offre d'emploi",
     "Développeur Android senior (H/F)\nCDI · Bordeaux · télétravail partiel\nKotlin, Jetpack Compose, tests "
     "automatisés. Candidature avant le 30 novembre.", None),
    ("linkedin-post", "en", "linkedin", 41, 9, "LinkedIn",
     "Excited to share that our team shipped offline search on mobile: everything runs on the device, "
     "no server involved.", None),
    # Music
    ("spotify-jazz", "en", "spotify", 4, 22, "Late Night Jazz",
     "Late Night Jazz\nPlaylist · 68 songs, 4 h 12 min\nSmooth piano and saxophone for quiet evenings", None),
    ("spotify-podcast", "fr", "spotify", 8, 8, "Histoires d'œuvres",
     "Histoires d'œuvres · Épisode 42\nLe Radeau de la Méduse, ou comment un naufrage est devenu un manifeste "
     "romantique. 38 min", None),
    # Codes and accounts
    ("otp-bank", "fr", "gmail", 0, 15, "Code de vérification",
     "Votre code de vérification est 731 904. Il expire dans 10 minutes. Ne le communiquez jamais.", None),
    ("ticket-support", "en", "gmail", 24, 10, "Support request received",
     "We received your request #CS-58201 about a damaged screen protector. A member of our team will reply "
     "within 48 hours.", None),
    ("wifi-router", "fr", "chrome", 50, 21, "Configuration du routeur",
     "Configuration du routeur\nAdresse de l'interface : 192.168.1.1\nIdentifiant admin, changer le mot de passe "
     "par défaut au premier accès.", "http://192.168.1.1/"),
    ("tracking-dhl", "en", "chrome", 3, 10, "Shipment tracking",
     "Shipment 7734 0091 5528 is out for delivery. Expected between 2 pm and 6 pm.",
     "https://track.example.com/7734009155"),
    # Health, admin
    ("pharmacy", "fr", "maps", 0, 18, "Pharmacie de garde",
     "Pharmacie du Marché — de garde ce soir\nOuverte jusqu'à 22:00\n3 avenue Foch", None),
    ("tax-reminder", "fr", "gmail", 44, 9, "Impôts : rappel",
     "Rappel : la date limite de paiement du solde de l'impôt est fixée au 15 décembre. "
     "Le prélèvement à la source continue normalement.", None),
    ("gym", "en", "agenda", 1, 6, "Gym",
     "Gym — leg day\nWednesday · 06:30 – 07:30\nSquats, lunges, 20 minutes of rowing", None),
    ("vet", "fr", "agenda", 19, 10, "Vétérinaire",
     "Vétérinaire pour Pixel\nVaccin annuel et vermifuge\nSamedi 28 novembre · 10:15", None),
    ("weather-storm", "fr", "chrome", 1, 20, "Alerte orange",
     "Alerte orange vents violents : rafales jusqu'à 110 km/h attendues cette nuit sur le littoral. "
     "Évitez les déplacements en bord de mer.", "https://meteo.example.fr/alertes"),
    ("weather-snow", "en", "chrome", 47, 7, "Snow forecast",
     "Snow forecast: 15 to 20 cm expected above 800 m tonight, roads may be closed in the mountains.",
     "https://weather.example.com/snow"),
]

# (id, text, type, language, expected keys)
QUERIES = [
    # Exact words
    ("q01", "tarte tatin", "exact", "fr", ["recipe-tatin"]),
    ("q02", "risotto champignons", "exact", "fr", ["recipe-risotto"]),
    ("q03", "chickpea curry", "exact", "en", ["recipe-curry"]),
    ("q04", "Le Petit Zinc", "exact", "fr", ["restaurant-zinc"]),
    ("q05", "Kumo Sushi", "exact", "en", ["restaurant-sushi"]),
    ("q06", "Monte Cinto", "exact", "fr", ["map-hike"]),
    ("q07", "vélo de ville", "exact", "fr", ["bike-sale"]),
    ("q08", "corner sofa", "exact", "en", ["sofa-sale"]),
    ("q09", "phares côte corse", "exact", "fr", ["article-lighthouses"]),
    ("q10", "pompe à chaleur", "exact", "fr", ["article-heat-pump"]),
    ("q11", "levain pain de campagne", "exact", "fr", ["article-bread"]),
    ("q12", "Palombaggia", "exact", "fr", ["insta-beach"]),
    ("q13", "lemon cake", "exact", "en", ["insta-cake"]),
    ("q14", "Late Night Jazz", "exact", "en", ["spotify-jazz"]),
    ("q15", "Radeau de la Méduse", "exact", "fr", ["spotify-podcast"]),
    ("q16", "pharmacie de garde", "exact", "fr", ["pharmacy"]),
    ("q17", "réunion parents-professeurs", "exact", "fr", ["event-parents"]),
    ("q18", "yoga class", "exact", "en", ["event-yoga"]),
    # Codes
    ("q19", "QXH7TP", "code", "fr", ["train-lyon"]),
    ("q20", "K7M2QZ", "code", "fr", ["flight-lisbon"]),
    ("q21", "402-7781934-5521063", "code", "fr", ["order-headphones"]),
    ("q22", "113-5528740-9912646", "code", "en", ["order-books"]),
    ("q23", "6A19284755031", "code", "fr", ["parcel-relay"]),
    ("q24", "HIVER25", "code", "fr", ["promo-code"]),
    ("q25", "FE-2026-104558", "code", "fr", ["invoice-power"]),
    ("q26", "CS-58201", "code", "en", ["ticket-support"]),
    ("q27", "5521-889-034", "code", "en", ["hotel-bruges"]),
    ("q28", "LV-30419", "code", "fr", ["rental-car"]),
    ("q29", "TP 4421", "code", "fr", ["flight-lisbon"]),
    ("q30", "192.168.1.1", "code", "fr", ["wifi-router"]),
    # Paraphrases
    ("q31", "à quelle heure part mon train pour Lyon", "paraphrase", "fr", ["train-lyon"]),
    ("q32", "billet d'avion pour le Portugal", "paraphrase", "fr", ["flight-lisbon"]),
    ("q33", "où sont les clés de l'appart", "paraphrase", "fr", ["msg-keys"]),
    ("q34", "code du réseau sans fil de la coloc", "paraphrase", "fr", ["msg-wifi"]),
    ("q35", "dessert aux pommes caramélisées", "paraphrase", "fr", ["recipe-tatin"]),
    ("q36", "comment déboucher ou réparer un tuyau qui goutte", "paraphrase", "fr", ["video-plumbing"]),
    ("q37", "tempête et vent fort sur la côte", "paraphrase", "fr", ["weather-storm"]),
    ("q38", "chauffage économique pour la maison", "paraphrase", "fr", ["article-heat-pump"]),
    ("q39", "cadeaux de Noël pour la famille", "paraphrase", "fr", ["note-gift-ideas"]),
    ("q40", "liste pour le supermarché", "paraphrase", "fr", ["note-groceries"]),
    ("q41", "rendez-vous chez le dentiste", "paraphrase", "fr", ["event-dentist"]),
    ("q42", "vaccin du chat", "paraphrase", "fr", ["vet"]),
    ("q43", "poste de développeur mobile", "paraphrase", "fr", ["linkedin-job"]),
    ("q44", "mouvement social dans les transports", "paraphrase", "fr", ["x-strike"]),
    ("q45", "anniversaire de la grand-mère", "paraphrase", "fr", ["msg-birthday"]),
    ("q46", "how long does an electric car battery last", "paraphrase", "en", ["article-ev-battery"]),
    ("q47", "tips to sleep better when days are short", "paraphrase", "en", ["article-sleep"]),
    ("q48", "someone to watch the kids on Friday night", "paraphrase", "en", ["msg-babysitter"]),
    ("q49", "help carrying furniture this weekend", "paraphrase", "en", ["msg-moving"]),
    ("q50", "beginner guitar lesson", "paraphrase", "en", ["video-guitar"]),
    ("q51", "photo of galaxies from a telescope", "paraphrase", "en", ["x-launch"]),
    ("q52", "hotel room in Belgium", "paraphrase", "en", ["hotel-bruges"]),
    # Temporal
    ("q53", "message de Camille ce soir", "temporal", "fr", ["msg-dinner-tuesday"]),
    ("q54", "colis hier", "temporal", "fr", ["parcel-relay"]),
    ("q55", "alerte météo hier soir", "temporal", "fr", ["weather-storm"]),
    ("q56", "concert dimanche", "temporal", "fr", ["insta-concert"]),
    ("q57", "commande il y a 4 jours", "temporal", "fr", ["order-headphones"]),
    ("q58", "recette la semaine dernière", "temporal", "fr", ["recipe-tatin"]),
    ("q59", "code de vérification aujourd'hui", "temporal", "fr", ["otp-bank"]),
    ("q60", "jazz playlist last week", "temporal", "en", ["spotify-jazz"]),
    ("q61", "gym yesterday", "temporal", "en", ["gym"]),
    ("q62", "tracking 3 days ago", "temporal", "en", ["tracking-dhl"]),
    # Application
    ("q63", "plage sur Instagram", "application", "fr", ["insta-beach"]),
    ("q64", "grève sur X", "application", "fr", ["x-strike"]),
    ("q65", "risotto sur YouTube", "application", "fr", ["recipe-risotto"]),
    ("q66", "billet sur SNCF Connect", "application", "fr", ["train-lyon"]),
    ("q67", "vélo sur leboncoin", "application", "fr", ["bike-sale"]),
    ("q68", "facture dans Gmail", "application", "fr", ["invoice-power"]),
    ("q69", "batteries on Reddit", "application", "en", ["article-ev-battery"]),
    ("q70", "sushi on Maps", "application", "en", ["restaurant-sushi"]),
    # Bilingual: query in one language, content in the other
    ("q71", "chickpea and coconut stew", "paraphrase", "bilingual", ["recipe-curry"]),
    ("q72", "pancake batter recipe", "paraphrase", "bilingual", ["recipe-crepes"]),
    ("q73", "train ticket to Lyon", "paraphrase", "bilingual", ["train-lyon"]),
    ("q74", "noise cancelling headphones order", "paraphrase", "bilingual", ["order-headphones"]),
    ("q75", "lighthouses of Corsica", "paraphrase", "bilingual", ["article-lighthouses"]),
    ("q76", "réservation d'hôtel à Bruges", "paraphrase", "bilingual", ["hotel-bruges"]),
    ("q77", "gâteau au citron", "paraphrase", "bilingual", ["insta-cake"]),
    ("q78", "cours de yoga le lundi", "paraphrase", "bilingual", ["event-yoga"]),
    ("q79", "réunion de lancement du projet", "paraphrase", "bilingual", ["note-meeting"]),
    ("q80", "prévisions de neige en montagne", "paraphrase", "bilingual", ["weather-snow"]),
]

# Fillers: everyday screens that answer no query.
FILLER_FR = [
    ("keep", "Note", "Penser à rappeler {name} pour {thing}."),
    ("whatsapp", "{name}", "{name} : Tu as vu le match hier ? Quelle fin incroyable !"),
    ("whatsapp", "{name}", "{name} : Je serai en retard de {n} minutes, désolé."),
    ("chrome", "Actualités", "Le conseil municipal a voté le budget {year} : {n} millions pour les écoles et la voirie."),
    ("chrome", "Sport", "Résumé : victoire {a}-{b} à domicile, l'équipe remonte à la {n}e place du classement."),
    ("gmail", "Lettre d'information", "Découvrez nos nouveautés de la semaine et nos conseils pour {thing}."),
    ("gmail", "Confirmation", "Votre abonnement a bien été renouvelé jusqu'au {day} {month}."),
    ("agenda", "{thing}", "{thing}\n{weekday} · {hour}:00 – {hour2}:00"),
    ("x", "X", "Quelqu'un a une recommandation de série pour ce week-end ? J'ai fini tout le reste."),
    ("reddit", "r/france", "Petit retour d'expérience sur {thing} : ça vaut le coup si on s'organise bien."),
    ("instagram", "Instagram", "{name}.photo · Balade en forêt ce matin, les couleurs d'automne 🍂"),
    ("youtube", "Vidéo", "Top {n} des astuces pour {thing} — ce que personne ne vous dit"),
    ("leboncoin", "Annonce", "{object} d'occasion\n{price} €\nBon état, à venir chercher sur place."),
    ("maps", "Itinéraire", "Itinéraire en voiture · {n} min · {km} km par la départementale"),
    ("linkedin", "LinkedIn", "{name} a publié : ravie de rejoindre une nouvelle équipe ce mois-ci !"),
    ("spotify", "Playlist", "Playlist du moment · {n} titres pour se concentrer"),
    ("amazon", "Recommandations", "Inspiré de vos achats : {object}, {object2}, accessoires"),
]
FILLER_EN = [
    ("keep", "Note", "Remember to call {name} about {thing}."),
    ("whatsapp", "{name}", "{name}: Running {n} minutes late, sorry!"),
    ("chrome", "News", "City council approves {year} budget: {n} million for schools and roads."),
    ("chrome", "Sports", "Recap: {a}-{b} home win, the team climbs to number {n} in the league."),
    ("gmail", "Newsletter", "This week's picks and our best advice on {thing}."),
    ("agenda", "{thing}", "{thing}\n{weekday} · {hour}:00 – {hour2}:00"),
    ("x", "X", "Any show recommendations for the weekend? I've watched everything else."),
    ("reddit", "r/AskUK", "Quick update on {thing}: worth it if you plan ahead."),
    ("youtube", "Video", "Top {n} tips for {thing} that nobody tells you"),
    ("spotify", "Playlist", "Focus playlist · {n} tracks"),
]
WORDS = {
    "name": ["Nicolas", "Sophie", "Karim", "Manon", "Hugo", "Chloé", "Yanis", "Anaïs", "Paul", "Zoé"],
    "thing_fr": ["le jardinage", "le télétravail", "les économies d'énergie", "le rangement", "la course à pied",
                 "les impôts", "la photo", "le covoiturage"],
    "thing_en": ["gardening", "remote work", "saving energy", "decluttering", "running", "photography",
                 "car sharing"],
    "object_fr": ["Lampe de bureau", "Poussette", "Table basse", "Trottinette", "Machine à café", "Étagère"],
    "weekday_fr": ["Lundi", "Mardi", "Mercredi", "Jeudi", "Vendredi"],
    "weekday_en": ["Monday", "Tuesday", "Wednesday", "Thursday", "Friday"],
    "month_fr": ["janvier", "mars", "juin", "septembre", "décembre"],
}


def timestamp(days_ago: int, hour: int, minute: int = 0) -> int:
    day = (NOW - timedelta(days=days_ago)).date()
    moment = datetime(day.year, day.month, day.day, hour, minute, tzinfo=ZONE)
    return int(moment.timestamp() * 1000)


def filler(rng: random.Random, index: int) -> dict:
    french = index % 3 != 2
    app, title, template = rng.choice(FILLER_FR if french else FILLER_EN)
    suffix = "fr" if french else "en"
    hour = rng.randint(7, 20)
    values = {
        "name": rng.choice(WORDS["name"]),
        "thing": rng.choice(WORDS[f"thing_{suffix}"]),
        "object": rng.choice(WORDS["object_fr"]),
        "object2": rng.choice(WORDS["object_fr"]),
        "weekday": rng.choice(WORDS[f"weekday_{suffix}"]),
        "month": rng.choice(WORDS["month_fr"]),
        "n": rng.randint(2, 40),
        "a": rng.randint(0, 4),
        "b": rng.randint(0, 3),
        "km": rng.randint(3, 60),
        "price": rng.randint(5, 300),
        "year": 2027,
        "day": rng.randint(1, 28),
        "hour": hour,
        "hour2": hour + 1,
    }
    text = template.format(**values)
    package, label = APPS[app]
    days_ago = rng.randint(0, DAYS - 1)
    at = timestamp(days_ago, rng.randint(6, 23), rng.randint(0, 59))
    return {
        "key": f"filler-{index:03d}",
        "lang": suffix,
        "package": package,
        "app": label,
        "title": title.format(**values),
        "url": None,
        "text": text,
        "lastSeenAt": at,
    }


def main() -> None:
    rng = random.Random(SEED)
    corpus = []
    for key, lang, app, days_ago, hour, title, text, url in ANCHORS:
        package, label = APPS[app]
        corpus.append({
            "key": key,
            "lang": lang,
            "package": package,
            "app": label,
            "title": title,
            "url": url,
            "text": text,
            "lastSeenAt": timestamp(days_ago, hour, 10),
        })
    corpus += [filler(rng, i) for i in range(FILLERS)]
    keys = {item["key"] for item in corpus}
    assert len(keys) == len(corpus), "duplicate keys"
    for query in QUERIES:
        assert all(key in keys for key in query[4]), query

    OUT.mkdir(parents=True, exist_ok=True)
    with (OUT / "corpus.jsonl").open("w", encoding="utf-8") as out:
        for item in corpus:
            out.write(json.dumps(item, ensure_ascii=False) + "\n")
    with (OUT / "queries.jsonl").open("w", encoding="utf-8") as out:
        header = {"now": int(NOW.timestamp() * 1000), "zone": str(ZONE), "apps": sorted(
            ({"package": p, "label": l} for p, l in APPS.values()), key=lambda a: a["package"])}
        out.write(json.dumps({"meta": header}, ensure_ascii=False) + "\n")
        for qid, text, kind, lang, expected in QUERIES:
            out.write(json.dumps(
                {"id": qid, "text": text, "type": kind, "lang": lang, "expected": expected},
                ensure_ascii=False,
            ) + "\n")
    print(f"{len(corpus)} captures ({len(ANCHORS)} anchors), {len(QUERIES)} queries")


if __name__ == "__main__":
    main()
