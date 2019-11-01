package com.example.util.exposed.postgres.extensions.earthdistance


import org.jetbrains.exposed.sql.ColumnType
import org.postgresql.util.*
import java.sql.PreparedStatement


data class PGEarthBox(val c1: PGEarthPointLocation, val c2: PGEarthPointLocation)

fun PGEarthBox.toPgValue(): String = "${c1.toPgValue()},${c2.toPgValue()}"

class PGEarthBoxColumnType : ColumnType() {
    companion object {
        private val patternTokenizeIntoPoints: Regex = "\\((.*?)\\)".toRegex()
    }

    private val pgObjectType: String = "cube"
    override fun sqlType(): String = pgObjectType

    override fun valueFromDB(value: Any): PGEarthBox {
        var pgTypeGiven: String? = null
        var pgValueGiven: String? = null
        return try {
            value as PGobject
            pgTypeGiven = value.type
            pgValueGiven = value.value

            val pointsText: List<String> = patternTokenizeIntoPoints.findAll(pgValueGiven)
                    .map { it.value.trim() }
                    .filter { it.isNotBlank() }
                    .toList()
                    .take(2)

            val points: List<PGEarthPointLocation> = pointsText.map {
                PGtokenizer.removePara(it)
                val t = PGtokenizer(PGtokenizer.removePara(it), ',')
                PGEarthPointLocation(
                        x = java.lang.Double.parseDouble(t.getToken(0)),
                        y = java.lang.Double.parseDouble(t.getToken(1)),
                        z = java.lang.Double.parseDouble(t.getToken(2))
                )
            }

            PGEarthBox(c1 = points[0], c2 = points[1])
        } catch (e: NumberFormatException) {
            throw PSQLException(
                    GT.tr("Conversion to type $pgTypeGiven -> ${this::class.qualifiedName} failed: $pgValueGiven."),
                    PSQLState.DATA_TYPE_MISMATCH, e)
        }
    }

    override fun setParameter(stmt: PreparedStatement, index: Int, value: Any?) {
        val obj = PGobject()
        obj.type = sqlType()
        obj.value = when (value) {
            null -> null
            else -> try {
                (value as PGEarthPointLocation).toPgValue()
            } catch (all: Exception) {
                throw PSQLException(
                        "Failed to setParameter at index: $index - value: $value ! reason: ${all.message}",
                        PSQLState.DATA_TYPE_MISMATCH,
                        all
                )
            }
        }
        stmt.setObject(index, obj)
    }

    override fun notNullValueToDB(value: Any): PGEarthPointLocation {
        return value as PGEarthPointLocation
    }

    override fun nonNullValueToString(value: Any): String {
        val sinkValue: PGEarthPointLocation = notNullValueToDB(value)
        return "'${sinkValue.toPgValue()}'"
    }
}

