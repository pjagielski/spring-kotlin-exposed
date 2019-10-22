package com.example.api.places.geosearch.dsl

import com.example.api.places.common.db.PlaceRecord
import com.example.api.places.common.db.PlaceTable
import com.example.api.places.common.rest.response.toPlaceDto
import com.example.api.places.geosearch.PlacesGeoSearchRequest
import com.example.api.places.geosearch.PlacesGeoSearchResponse
import com.example.api.places.geosearch.PlacesGeoSearchResponseItem
import com.example.util.exposed.functions.postgres.earth_box
import com.example.util.exposed.functions.postgres.earth_distance
import com.example.util.exposed.functions.postgres.ll_to_earth
import com.example.util.exposed.functions.postgres.pgContains
import com.example.util.exposed.query.toSQL
import com.example.util.time.durationToNowInMillis
import mu.KLogging
import org.funktionale.tries.Try
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.intParam
import org.jetbrains.exposed.sql.select
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Instant

private typealias Request = PlacesGeoSearchRequest
private typealias Response = PlacesGeoSearchResponse
private typealias ResponseItem = PlacesGeoSearchResponseItem

@Component
class GeoSearchDslHandler {
    companion object : KLogging()

    @Transactional(readOnly = true)
    fun handle(req: Request): Response {
        val startedAt = Instant.now()
        return Try { handleInternal(req) }
                .log(req, startedAt)
                .get()
    }

    private fun handleInternal(req: Request): Response = req
            .let(::search)
            .let(::mapToResponse)

    private fun mapToResponse(source: SearchResult): Response = source.items
            .map { ResponseItem(distance = it.distance, place = it.placeRecord.toPlaceDto()) }
            .let { Response(items = it) }

    private fun search(req: Request): SearchResult {
        val logCtxInfo: String = req.logCtxInfo
        val startedAt = Instant.now()

        val reqEarthExpr = ll_to_earth(
                latitude = req.payload.latitude, longitude = req.payload.longitude
        )
        val dbEarthExpr = ll_to_earth(
                latitude = PLACE.latitude, longitude = PLACE.longitude
        )
        val earthDistanceExpr = earth_distance(
                fromEarth = reqEarthExpr, toEarth = dbEarthExpr
        )

        val reqEarthBoxExpr = earth_box(
                earth = reqEarthExpr,
                earthDistance = intParam(req.payload.radiusInMeter)
        )

        return PLACE
                .slice(
                        earthDistanceExpr,
                        *PLACE.columns.toTypedArray()
                )
                .select {
                    (PLACE.active eq true)
                            .and(earthDistanceExpr.lessEq(req.payload.radiusInMeter.toDouble()))
                            .and(reqEarthBoxExpr.pgContains(dbEarthExpr))
                }
                .orderBy(
                        Pair(earthDistanceExpr, SortOrder.ASC),
                        Pair(PLACE.createdAt, SortOrder.ASC),
                        Pair(PLACE.place_id, SortOrder.ASC)
                )
                .limit(n = req.payload.limit, offset = req.payload.offset)
                .also {
                    logger.info("SEARCH (dsl): $logCtxInfo - prepare sql: ${it.toSQL()}")
                }
                .map {
                    val placeRecord: PlaceRecord = PLACE.mapRowToRecord(it)
                    val distance: Double = it[earthDistanceExpr]
                    SearchResult.Item(distance = distance, placeRecord = placeRecord)
                }
                .let { SearchResult(items = it) }
                .also {
                    logger.info {
                        "SEARCH (dsl): COMPLETE. $logCtxInfo" +
                                " - duration: ${startedAt.durationToNowInMillis()} ms " +
                                " - result.items.count: ${it.items.size}"
                    }
                }
    }

    private fun Try<Response>.log(req: Request, startedAt: Instant): Try<Response> {
        val logCtxInfo: String = req.logCtxInfo
        this.onFailure {
            logger.error {
                "Handler FAILED! $logCtxInfo" +
                        " - duration: ${startedAt.durationToNowInMillis()} ms" +
                        " - reason: ${it.message}" +
                        " - req: $req"
            }
        }
        this.onSuccess {
            logger.info {
                "Handler Success. $logCtxInfo" +
                        " - duration: ${startedAt.durationToNowInMillis()} ms" +
                        " - response.items.count: ${it.items.size}"
            }
        }

        return this
    }

}

private val PLACE = PlaceTable

private data class SearchResult(
        val items: List<Item>
) {
    data class Item(val distance: Double, val placeRecord: PlaceRecord)
}

private val Request.logCtxInfo: String
    get() = "(logId: $logId)"
