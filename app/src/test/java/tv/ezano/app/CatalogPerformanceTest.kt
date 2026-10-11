package tv.ezano.app

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CatalogPerformanceTest {
    @Test fun indexedSearchMatchesLegacyTailSearchWithoutDecodingPages() = runBlocking {
        val builder = CompactCatalog.Builder()
        for (i in 0 until 76_000) {
            val kind = when (i % 3) { 0 -> Kind.MOVIE; 1 -> Kind.SERIES; else -> Kind.LIVE }
            val name = when (i) {
                75_997 -> "Needle Moonlight"
                75_998 -> "needle: The Last Journey"
                75_999 -> "A Quiet NEEDLE"
                else -> "Catalog title $i"
            }
            builder.add(Channel(
                id = "catalog:$i", provider = "provider:${i % 4}", sourceId = i.toString(),
                name = name, url = "https://media.example.test/vod/$i?token=provider-token",
                kind = kind, group = "Collection ${i % 12}",
                description = "A moderately detailed provider synopsis for title $i. ".repeat(8),
                logo = "https://images.example.test/posters/$i.jpg",
                backdrop = "https://images.example.test/backdrops/$i.jpg"
            ))
        }
        val catalog = builder.build()
        val queries = listOf("moonlight", "last journey", "quiet needle")

        suspend fun indexed(query: String) = catalog.searchCatalog {
            it.name.contains(query, ignoreCase = true)
        }
        fun legacy(query: String) = catalog.selectChannels {
            it.name.contains(query, ignoreCase = true)
        }
        suspend fun timedIndex() = queries.map { query ->
            val started = System.nanoTime()
            val ids = indexed(query).catalogLabels().map { it.id }.toList()
            (System.nanoTime() - started) / 1_000_000.0 to ids
        }
        fun timedLegacy() = queries.map { query ->
            val started = System.nanoTime()
            val ids = legacy(query).map { it.id }
            (System.nanoTime() - started) / 1_000_000.0 to ids
        }

        val indexedRuns = List(3) { timedIndex() }
        assertEquals("Indexed catalog queries must not inflate artwork/URL pages", 0, catalog.decodedPages.get())
        val legacyRuns = List(3) { timedLegacy() }
        indexedRuns.first().indices.forEach { queryIndex ->
            val expected = legacyRuns.first()[queryIndex].second
            indexedRuns.forEach { assertEquals(expected, it[queryIndex].second) }
            legacyRuns.forEach { assertEquals(expected, it[queryIndex].second) }
        }

        val indexedMedian = median(indexedRuns.flatten().map { it.first })
        val legacyMedian = median(legacyRuns.flatten().map { it.first })
        println("Catalog search median across 9 queries: indexed=${"%.2f".format(indexedMedian)}ms legacy=${"%.2f".format(legacyMedian)}ms")
    }

    @Test fun indexedHomeRecommendationsMatchSequenceWithEligibilityAndLabelFilters() {
        val channels = (0 until 1_200).map { i ->
            val safe = i % 5 == 0
            val kind = when (i % 4) { 0 -> Kind.MOVIE; 1 -> Kind.SERIES; else -> Kind.LIVE }
            Channel(
                id = "home:$i", provider = "provider:${i % 3}", sourceId = i.toString(),
                name = if (i % 29 == 0) "Family special $i" else "Title $i",
                url = "https://media.example.test/$i?token=secret", kind = kind,
                group = if (safe) "[EN] FAMILY" else "[EN] NEW RELEASES",
                genre = if (safe) "Family" else "Drama", language = if (i % 17 == 0) "pl" else "en",
                country = if (i % 2 == 0) "US" else "CA",
                certification = when { i % 19 == 0 -> "R"; safe -> "PG"; else -> "" },
                rating = (i % 100).toString(), popularity = i.toDouble()
            )
        }
        val catalog = CompactCatalog.Builder().apply { addAll(channels) }.build()
        val allowed: (CatalogLabel) -> Boolean = { it.provider == "provider:1" && it.group.contains("FAMILY") }
        val indexed = HomeRecommendations.selectIndexed(catalog, perKind = 18, allowed = allowed)

        assertTrue(indexed.mixed.isNotEmpty())
        assertTrue("Only selected records may be decoded", catalog.decodedPages.get() <= indexed.mixed.size)
        val expected = HomeRecommendations.select(channels.asSequence().filter { allowed(CatalogLabel.from(it)) }, perKind = 18)
        assertEquals(expected.movies.map { it.id }, indexed.movies.map { it.id })
        assertEquals(expected.shows.map { it.id }, indexed.shows.map { it.id })
        assertTrue(channels.any { !HomeRecommendations.isEligible(it) })
        assertTrue(channels.any { HomeRecommendations.isEligible(it) })
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
    }
}
