package persistence.postgresql

import org.specs2.mutable.Specification
import persistence.querylang.{ QueryParser, BooleanExpr }

import scala.util.Try

/**
 * Created by Karel Maesen, Geovise BVBA on 23/01/15.
 */
class PGQueryRenderSpec extends Specification {

  implicit val renderContext = RenderContext("geometry", Some("SRID=31370;POLYGON((1 1,100 1,100 100,1 100,1 1))"))

  "The PGJsonQueryRenderer " should {

    val renderer = PGJsonQueryRenderer

    "properly render boolean expressions containing equality expresssions " in {
      val expr: BooleanExpr = QueryParser.parse("ab.cd = 12").get
      compressWS(renderer.render(expr)) === "json_extract_path_text(json, 'ab','cd')::decimal = ( 12 )"
    }

    "properly render comparison expressions contain boolean literal" in {
      val expr = QueryParser.parse("ab.cd = TRUE").get
      compressWS(renderer.render(expr)) === "json_extract_path_text(json, 'ab','cd')::bool = ( true )"
    }

    "properly render simple equality expression " in {
      val expr = QueryParser.parse("properties.foo = 'bar1'").get
      compressWS(renderer.render(expr)) === "json_extract_path_text(json, 'properties','foo')::text = ( 'bar1' )"
    }

    "properly render boolean expressions containing comparison expresssions other than equality " in {

      val expr1 = QueryParser.parse("ab.cd > 12").get
      val expr2 = QueryParser.parse("ab.cd >= 12").get
      val expr3 = QueryParser.parse("ab.cd != 'abc'").get

      (
        compressWS(renderer.render(expr1)) === "json_extract_path_text(json, 'ab','cd')::decimal > ( 12 )"
      ) and (
          compressWS(renderer.render(expr2)) === "json_extract_path_text(json, 'ab','cd')::decimal >= ( 12 )"
        ) and (
            compressWS(renderer.render(expr3)) === "json_extract_path_text(json, 'ab','cd')::text != ( 'abc' )"
          )
    }

    "properly render negated expressions " in {
      val expr1 = QueryParser.parse("not ab.cd = 12").get
      compressWS(renderer.render(expr1)) === "NOT ( json_extract_path_text(json, 'ab','cd')::decimal = ( 12 ) )"
    }

    "properly render AND expressions " in {
      val expr1 = QueryParser.parse(" (ab = 12) and (cd > 'c') ").get
      compressWS(renderer.render(expr1)) ===
        "( json_extract_path_text(json, 'ab')::decimal = ( 12 ) ) AND ( json_extract_path_text(json, 'cd')::text > ( 'c' ) )"
    }

    "properly render OR expressions " in {
      val expr1 = QueryParser.parse(" (ab = 12) or (cd > 'c') ").get
      compressWS(renderer.render(expr1)) ===
        "( json_extract_path_text(json, 'ab')::decimal = ( 12 ) ) OR ( json_extract_path_text(json, 'cd')::text > ( 'c' ) )"
    }

    "properly render a boolean literal as a boolean expression" in {
      val expr = QueryParser.parse("TRUE").get
      renderer.render(expr) === " true "
    }

    "property render an IN predicate expression" in {
      val expr1 = QueryParser.parse(" a.b in (1,2,3) ").get
      compressWS(renderer.render(expr1)) ===
        "json_extract_path_text(json, 'a','b')::decimal in (3,2,1)"
    }

    "properly render simple regex expression " in {
      val expr = QueryParser.parse("properties.foo ~ /bar1.*/").get
      compressWS(renderer.render(expr)) === "json_extract_path_text(json, 'properties','foo')::text ~ 'bar1.*'"
    }

    "properly render simple like expression " in {
      val expr = QueryParser.parse("properties.foo like 'a%bcd'").get
      compressWS(renderer.render(expr)) === "json_extract_path_text(json, 'properties','foo')::text ilike 'a%bcd'"
    }

    "properly render IS NULL expression " in {
      val expr = QueryParser.parse("properties.foo is null").get
      compressWS(renderer.render(expr)) === "json_extract_path_text(json, 'properties','foo') is null"
    }

    "properly render IS NOT NULL expression " in {
      val expr = QueryParser.parse("properties.foo is not null").get
      compressWS(renderer.render(expr)) === "json_extract_path_text(json, 'properties','foo') is not null"
    }

    "properly render Intersects bbox  expression" in {
      val expr = QueryParser.parse("intersects bbox").get
      compressWS(renderer.render(expr)) === "ST_Intersects( geometry, 'SRID=31370;POLYGON((1 1,100 1,100 100,1 100,1 1))' )"
    }

    "properly render Intersects with Wkt Literal  expression" in {
      val expr = QueryParser.parse("intersects 'SRID=31370;POINT(10 12)'").get
      compressWS(renderer.render(expr)) === "ST_Intersects( geometry, 'SRID=31370;POINT(10 12)' )"
    }

  }

  "The PGRegularQueryRenderer " should {

    val renderer = PGRegularQueryRenderer

    "properly render boolean expressions containing equality expresssions " in {
      val expr: BooleanExpr = QueryParser.parse("ab.cd = 12").get
      compressWS(renderer.render(expr)) === "ab.cd = ( 12 )"
    }

    "properly render comparison expressions contain boolean literal" in {
      val expr = QueryParser.parse("ab.cd = TRUE").get
      compressWS(renderer.render(expr)) === "ab.cd = ( true )"
    }

    "properly render simple equality expression " in {
      val expr = QueryParser.parse("properties.foo = 'bar1'").get
      compressWS(renderer.render(expr)) === "foo = ( 'bar1' )"
    }

    "properly render boolean expressions containing comparison expresssions other than equality " in {

      val expr1 = QueryParser.parse("ab.cd > 12").get
      val expr2 = QueryParser.parse("ab.cd >= 12").get
      val expr3 = QueryParser.parse("ab.cd != 'abc'").get

      (
        compressWS(renderer.render(expr1)) === "ab.cd > ( 12 )"
      ) and (
          compressWS(renderer.render(expr2)) === "ab.cd >= ( 12 )"
        ) and (
            compressWS(renderer.render(expr3)) === "ab.cd != ( 'abc' )"
          )
    }

    "properly render negated expressions " in {
      val expr1 = QueryParser.parse("not ab.cd = 12").get
      compressWS(renderer.render(expr1)) === "NOT ( ab.cd = ( 12 ) )"
    }

    "properly render AND expressions " in {
      val expr1 = QueryParser.parse(" (ab = 12) and (cd > 'c') ").get
      compressWS(renderer.render(expr1)) ===
        "( ab = ( 12 ) ) AND ( cd > ( 'c' ) )"
    }

    "properly render OR expressions " in {
      val expr1 = QueryParser.parse(" (ab = 12) or (cd > 'c') ").get
      compressWS(renderer.render(expr1)) ===
        "( ab = ( 12 ) ) OR ( cd > ( 'c' ) )"
    }

    "properly render a boolean literal as a boolean expression" in {
      val expr = QueryParser.parse("TRUE").get
      renderer.render(expr) === " true "
    }

    "property render an IN predicate expression" in {
      val expr1 = QueryParser.parse(" a.b in (1,2,3) ").get
      compressWS(renderer.render(expr1)) ===
        "a.b in (3,2,1)"
    }

    "properly render simple regex expression " in {
      val expr = QueryParser.parse("properties.foo ~ /bar1.*/").get
      compressWS(renderer.render(expr)) === "foo ~ 'bar1.*'"
    }

    "properly render simple like expression " in {
      val expr = QueryParser.parse("properties.foo like 'a%bcd'").get
      compressWS(renderer.render(expr)) === "foo ilike 'a%bcd'"
    }

    "properly render IS NULL expression " in {
      val expr = QueryParser.parse("properties.foo is null").get
      compressWS(renderer.render(expr)) === "foo is null"
    }

    "properly render IS NOT NULL expression " in {
      val expr = QueryParser.parse("properties.foo is not null").get
      compressWS(renderer.render(expr)) === "foo is not null"
    }

    "properly render Intersects bbox  expression" in {
      val expr = QueryParser.parse("intersects bbox").get
      compressWS(renderer.render(expr)) === "ST_Intersects( geometry, 'SRID=31370;POLYGON((1 1,100 1,100 100,1 100,1 1))' )"
    }

    "properly render Intersects with Wkt Literal  expression" in {
      val expr = QueryParser.parse("intersects 'SRID=31370;POINT(10 12)'").get
      compressWS(renderer.render(expr)) === "ST_Intersects( geometry, 'SRID=31370;POINT(10 12)' )"
    }

  }

  private def compressWS(str: String) = str.replaceAll(" +", " ").trim

}
