package fr.corsicajack.seekmind.core.testing

import fr.corsicajack.seekmind.core.database.DatabaseHandle
import fr.corsicajack.seekmind.core.database.store.NewCapture
import fr.corsicajack.seekmind.core.database.store.NewChunk
import fr.corsicajack.seekmind.core.database.store.NewEmbedding
import kotlin.random.Random

/**
 * Synthetic captures with realistic texts built from templates (ticket M1-07): applications, dates
 * spread over [days], one to several lines of 150 to 900 characters. Deterministic for a [seed]; every
 * value is invented (example.* domains, made-up codes and amounts, common first names).
 */
class SyntheticCorpus(
    private val seed: Long = 20_261_112L,
    private val now: Long = Models.AT,
    private val days: Int = 90,
) {
    /** The [index]-th capture: the same index, the same capture. */
    fun capture(index: Int): NewCapture {
        val random = Random(seed * 1_000_003 + index)
        val app = APPS[random.nextInt(APPS.size)]
        val lines = buildList {
            add(fill(app.templates[random.nextInt(app.templates.size)], random))
            repeat(random.nextInt(1, 6)) { add(fill(COMMON[random.nextInt(COMMON.size)], random)) }
        }
        val text = lines.joinToString("\n").take(900)
        // Older captures for smaller indexes: insertion order follows time, as on a phone.
        val at = now - days * DAY + (index.toLong() * 7_919 % (days * DAY / 1_000)) * 1_000
        val url = if (app.browser) {
            "https://www.${DOMAINS[
                random.nextInt(
                    DOMAINS.size,
                ),
            ]}/${WORDS[random.nextInt(WORDS.size)]}"
        } else {
            null
        }
        val title = if (random.nextInt(3) == 0) null else fill(TITLES[random.nextInt(TITLES.size)], random)
        return NewCapture(at, at, app.packageName, null, title, url, text, 1, random.nextLong())
    }

    /** Words found in the corpus, for queries of benchmarks: frequent and rare ones alike. */
    fun queryWord(random: Random): String = WORDS[random.nextInt(WORDS.size)]

    private fun fill(template: String, random: Random): String = SLOT.replace(template) { match ->
        when (match.value) {
            "{name}" -> NAMES[random.nextInt(NAMES.size)]
            "{city}" -> CITIES[random.nextInt(CITIES.size)]
            "{word}" -> WORDS[random.nextInt(WORDS.size)]
            "{n}" -> random.nextInt(2, 500).toString()
            "{price}" -> "${random.nextInt(1, 2_000)},${random.nextInt(10, 99)} €"
            "{code}" -> "CMD-2026-${random.nextInt(10_000, 99_999)}"
            "{time}" -> "${random.nextInt(7, 23)} h ${listOf("00", "15", "30", "45")[random.nextInt(4)]}"
            "{day}" -> DAYS_FR[random.nextInt(DAYS_FR.size)]
            else -> match.value
        }
    }

    private class App(val packageName: String, val browser: Boolean, val templates: List<String>)

    private companion object {
        const val DAY = 86_400_000L

        // Both braces escaped: Android's ICU regex engine rejects a bare "}".
        val SLOT = Regex("\\{[a-z]+\\}")
        val NAMES = listOf("Camille", "Alex", "Sacha", "Léa", "Hugo", "Inès", "Noah", "Emma", "Lucas", "Chloé")
        val CITIES = listOf("Ajaccio", "Bastia", "Lyon", "Nantes", "Lille", "Genève", "Bruxelles", "Montréal")
        val DAYS_FR = listOf("lundi", "mardi", "mercredi", "jeudi", "vendredi", "samedi", "dimanche")
        val DOMAINS = listOf("example.com", "example.org", "example.fr")
        val WORDS = listOf(
            "facture", "réunion", "train", "recette", "truffe", "pâtes", "vacances", "rendez-vous", "colis",
            "livraison", "abonnement", "remboursement", "météo", "pharmacie", "musée", "billet", "hôtel",
            "réservation", "budget", "rapport", "projet", "équipe", "école", "dentiste", "piscine", "marché",
            "concert", "voiture", "assurance", "loyer", "électricité", "garantie", "commande", "invoice", "meeting",
            "flight", "parcel", "weather", "recipe", "deadline", "contract", "playlist", "podcast", "ticket",
            "password", "update", "release", "garden",
        )
        val TITLES =
            listOf("Boîte de réception", "Conversation avec {name}", "{word} — {city}", "Commande {code}", "Notes")
        val COMMON = listOf(
            "Bonjour {name}, on se retrouve {day} à {time} pour parler du {word} ?",
            "Le {word} est prévu à {city} ; prévoir {n} personnes et un budget de {price}.",
            "Rappel : {word} {day} à {time}, pense à confirmer la réservation.",
            "Merci pour le {word}, c'était parfait. À bientôt !",
            "Total à payer : {price}. Paiement accepté pour la commande {code}.",
            "Your {word} is confirmed for {day} at {time}; reference {code}.",
            "{name} a partagé un document sur le {word} avec vous.",
            "Il fait beau à {city}, idéal pour la {word} de {day}.",
        )
        val APPS = listOf(
            App("com.example.mail", false, listOf("Objet : {word} du {day}", "De : {name} — {word} et {word}")),
            App("com.example.chat", false, listOf("{name} : tu as vu le {word} ?", "{name} : ok pour {time}")),
            App(
                "com.example.browser",
                true,
                listOf("Résultats pour « {word} {city} »", "{word} : tout savoir avant de partir"),
            ),
            App(
                "com.example.shop",
                false,
                listOf("Votre commande {code} est expédiée", "{word} en promotion à {price}"),
            ),
            App("com.example.notes", false, listOf("Liste : {word}, {word}, {word}", "Idées pour le {word}")),
            App(
                "com.example.travel",
                false,
                listOf("Train pour {city} : départ {day} à {time}", "Hôtel à {city}, {n} nuits"),
            ),
        )
    }
}

/** Loads a synthetic corpus into a database (ticket M1-07). */
class SyntheticDatabase(private val handle: DatabaseHandle, private val corpus: SyntheticCorpus = SyntheticCorpus()) {
    /**
     * Inserts captures [from] until [count], in transactions of [batch]; with [vectors], one chunk per
     * capture and its pseudo-random unit vector. Returns the duration, ms.
     */
    suspend fun populate(count: Int, vectors: Boolean = false, from: Int = 0, batch: Int = 1_000): Long {
        val start = System.nanoTime()
        var index = from
        while (index < count) {
            val end = minOf(count, index + batch)
            val items = (index until end).map { i ->
                val capture = corpus.capture(i)
                capture to if (vectors) listOf(NewChunk(0, 0, minOf(capture.text.length, 800))) else emptyList()
            }
            handle.captures.insertAll(items)
            if (vectors) {
                val pending = handle.vectors.pendingChunks(end - index)
                handle.vectors.saveEmbeddings(
                    pending.map { chunk ->
                        val vector = TestVectors.unit(seed = chunk.captureId)
                        NewEmbedding(
                            chunkId = chunk.chunkId,
                            captureId = chunk.captureId,
                            modelId = FakeEmbeddingEngine.MODEL_ID,
                            vecI8 = TestVectors.int8(vector),
                            vecBin = TestVectors.binary(vector),
                        )
                    },
                )
            }
            index = end
        }
        return (System.nanoTime() - start) / 1_000_000
    }
}
