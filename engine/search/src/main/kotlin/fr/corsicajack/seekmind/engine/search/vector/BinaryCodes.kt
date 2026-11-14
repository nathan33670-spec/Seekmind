package fr.corsicajack.seekmind.engine.search.vector

import fr.corsicajack.seekmind.core.model.SearchFilters

/**
 * The in-memory part of the vector index (docs/specs/recherche.md §6.1): one entry per vector in
 * parallel primitive arrays, oldest first. Not thread-safe: [VectorIndex] guards it.
 */
internal class BinaryCodes(private val words: Int, private val dimensions: Int = words * Long.SIZE_BITS) {
    var size = 0
        private set

    private var codes = LongArray(0)
    private var int8 = ByteArray(0)
    private var embeddingIds = LongArray(0)
    private var captureIds = LongArray(0)
    private var firstSeen = LongArray(0)
    private var lastSeen = LongArray(0)
    private var packageIndices = IntArray(0)

    private val packages = ArrayList<String>()
    private val packageIndex = HashMap<String, Int>()

    /** The id of the last vector added; 0 when empty. */
    val lastEmbeddingId: Long get() = if (size == 0) 0L else embeddingIds[size - 1]

    fun add(
        embeddingId: Long,
        captureId: Long,
        first: Long,
        last: Long,
        packageName: String,
        code: LongArray,
        vector: ByteArray,
    ) {
        require(code.size == words) { "a code holds $words words: ${code.size}" }
        require(vector.size == dimensions) { "an int8 vector holds $dimensions bytes: ${vector.size}" }
        require(embeddingId > lastEmbeddingId) {
            "vectors come in increasing id order: $embeddingId after $lastEmbeddingId"
        }
        ensureCapacity(size + 1)
        code.copyInto(codes, size * words)
        vector.copyInto(int8, size * dimensions)
        embeddingIds[size] = embeddingId
        captureIds[size] = captureId
        firstSeen[size] = first
        lastSeen[size] = last
        packageIndices[size] = packageIndex.getOrPut(packageName) { packages.size.also { packages += packageName } }
        size++
    }

    fun embeddingId(index: Int) = embeddingIds[index]

    fun captureId(index: Int) = captureIds[index]

    fun lastSeen(index: Int) = lastSeen[index]

    /** Bytes held by the arrays, for the diagnostics. */
    val bytes: Long get() = codes.size * 8L + int8.size +
        (embeddingIds.size + captureIds.size + firstSeen.size + lastSeen.size) * 8L +
        packageIndices.size * 4L

    /** `Σ query[i] × int8[i]` for the vector at [index]: the rescore of docs/specs/embeddings.md §6, before `/ S`. */
    fun dotInt8(index: Int, query: FloatArray): Float {
        require(query.size == dimensions) { "expected $dimensions components, got ${query.size}" }
        val offset = index * dimensions
        var sum = 0f
        for (d in 0 until dimensions) sum += query[d] * int8[offset + d]
        return sum
    }

    /**
     * Allowed packages as a mask over the package table, or null for every package. Packages absent
     * from the index match nothing.
     */
    fun packageMask(filters: SearchFilters): BooleanArray? {
        if (filters.packages.isEmpty()) return null
        return BooleanArray(packages.size) { packages[it] in filters.packages }
    }

    /**
     * The [into] closest vectors to [query] by Hamming distance among those that pass the filters, newest
     * first on equal distance; returns how many were kept. Allocates nothing: [into] and [distances] are the
     * caller's buffers, used as a bounded max-heap on the distance.
     */
    fun preselect(
        query: LongArray,
        from: Long?,
        to: Long?,
        mask: BooleanArray?,
        into: IntArray,
        distances: IntArray,
    ): Int {
        require(query.size == words) { "a code holds $words words: ${query.size}" }
        val capacity = minOf(into.size, distances.size)
        if (capacity == 0) return 0
        var count = 0
        // Newest first, so that an older vector at the same distance never displaces a newer one.
        for (i in size - 1 downTo 0) {
            if (from != null && lastSeen[i] < from) continue
            if (to != null && firstSeen[i] > to) continue
            if (mask != null && !mask[packageIndices[i]]) continue
            var distance = 0
            val offset = i * words
            for (w in 0 until words) distance += java.lang.Long.bitCount(query[w] xor codes[offset + w])
            if (count < capacity) {
                into[count] = i
                distances[count] = distance
                siftUp(into, distances, count)
                count++
            } else if (distance < distances[0]) {
                into[0] = i
                distances[0] = distance
                siftDown(into, distances, count)
            }
        }
        return count
    }

    private fun ensureCapacity(needed: Int) {
        if (needed <= embeddingIds.size) return
        val capacity = maxOf(needed, embeddingIds.size * 2, INITIAL_CAPACITY)
        codes = codes.copyOf(capacity * words)
        int8 = int8.copyOf(capacity * dimensions)
        embeddingIds = embeddingIds.copyOf(capacity)
        captureIds = captureIds.copyOf(capacity)
        firstSeen = firstSeen.copyOf(capacity)
        lastSeen = lastSeen.copyOf(capacity)
        packageIndices = packageIndices.copyOf(capacity)
    }

    private companion object {
        const val INITIAL_CAPACITY = 1_024

        fun siftUp(items: IntArray, keys: IntArray, start: Int) {
            var child = start
            while (child > 0) {
                val parent = (child - 1) / 2
                if (keys[parent] >= keys[child]) return
                swap(items, keys, parent, child)
                child = parent
            }
        }

        fun siftDown(items: IntArray, keys: IntArray, count: Int) {
            var parent = 0
            while (true) {
                val left = 2 * parent + 1
                if (left >= count) return
                val right = left + 1
                val largest = if (right < count && keys[right] > keys[left]) right else left
                if (keys[parent] >= keys[largest]) return
                swap(items, keys, parent, largest)
                parent = largest
            }
        }

        fun swap(items: IntArray, keys: IntArray, a: Int, b: Int) {
            val item = items[a]
            items[a] = items[b]
            items[b] = item
            val key = keys[a]
            keys[a] = keys[b]
            keys[b] = key
        }
    }
}
