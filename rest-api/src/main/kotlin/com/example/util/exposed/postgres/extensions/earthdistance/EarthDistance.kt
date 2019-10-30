package com.example.util.exposed.postgres.extensions.earthdistance

import com.example.util.exposed.functions.postgres.doubleParam
import org.jetbrains.exposed.sql.*
import org.postgresql.util.*
import java.sql.PreparedStatement

// select earth() -> returns the assumed radius of te earth as float8 ( SELECT '6378168'::float8 )
fun earth(): CustomFunction<Double> {
    val fn = CustomFunction<Double>("earth", DoubleColumnType())
    return fn
}



/**

select ll_to_earth( 11.1 , 20.0 ); -> returns (5881394.65979286, 2140652.5921368, 1227937.44619261)
select latitude('(5881394.65979286, 2140652.5921368, 1227937.44619261)'::earth); -> returns 11.1

fun ll_to_earth(latitude:Double?, longitude:Double?):PGEarthPointLocation?

 */



data class PGEarthPointLocation(val x:Double, val y:Double, val z:Double)
class PGEarthPointLocationColumnType: ColumnType() {
    override fun sqlType(): String  = "cube"

    override fun valueFromDB(value: Any): PGEarthPointLocation {
        var pgType:String?=null
        var pgValue:String?=null
        return try {
            value as PGobject
            pgType=value.type
            pgValue=value.value
            val t = PGtokenizer(PGtokenizer.removePara(pgValue), ',')
            PGEarthPointLocation(
                    x=java.lang.Double.parseDouble(t.getToken(0)),
                    y = java.lang.Double.parseDouble(t.getToken(1)),
                    z=java.lang.Double.parseDouble(t.getToken(2))
            )
        } catch (e: NumberFormatException) {
            throw PSQLException(
                    GT.tr("Conversion to type $pgType -> ${this.javaClass} failed: $pgValue."),
                    PSQLState.DATA_TYPE_MISMATCH, e)
        }
    }

    override fun setParameter(stmt: PreparedStatement, index: Int, value: Any?) {
        val obj = PGobject()
        obj.type = sqlType()
        obj.value = when(value) {
            null->null
            else-> try {
                value as PGEarthPointLocation
                "(${value.x}, ${value.y}, ${value.z})"
            } catch (all:Exception) {
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
        /*
        return when(value) {
            is PGEarthPointLocation -> value
            else -> super.notNullValueToDB(value)
        }

         */
    }
    override fun nonNullValueToString(value: Any): String {
        val sinkValue = notNullValueToDB(value)
        // "(${value.x}, ${value.y}, ${value.z})"
        return "'(${sinkValue.x}, ${sinkValue.y}, ${sinkValue.z})'"
    }
}

class NullExpr: Expression<Unit>() {
    override fun toQueryBuilder(queryBuilder: QueryBuilder) = queryBuilder {
        append(" NULL ")
    }
}
