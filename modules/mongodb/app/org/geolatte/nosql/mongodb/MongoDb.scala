package org.geolatte.nosql.mongodb

import org.geolatte.common.Feature
import collection.JavaConversions._
import org.geolatte.geom.codec.Wkb
import org.geolatte.geom.{Geometry, Envelope, ByteBuffer, ByteOrder}
import org.geolatte.geom.curve.{MortonContext, MortonCode}
import org.geolatte.nosql.{WindowQueryable, Source, Sink}
import com.mongodb.casbah.Imports._

import java.util
import play.Logger
import org.geolatte.geom.crs.CrsId
import play.api.libs.json.{Json, Writes}
import sun.misc.{BASE64Decoder, BASE64Encoder}


object MetadataIdentifiers {
  val MetadataCollection = "geolatte_nosql.collections"
  val ExtentField = "extent"
  val IndexStatsField = "index_stats"
  val IndexLevelField = "index_depth"
  val CollectionField = "collection"
  val Fields = Set(ExtentField, IndexStatsField, CollectionField)
}

object SpecialMongoProperties {

  val WKB = "_wkb"
  val MC = "_mc"
  val BBOX = "_bbox"
  val ID = "id"
  val _ID = "_id"

  val all = Set(WKB, MC, BBOX, ID, _ID)

  def isSpecialMongoProperty(key: String): Boolean = all.contains(key)

}

trait FeatureWithEnvelope extends Feature {

  def envelope: Envelope

}

class MongoDbSink(val collection: MongoCollection, val mortoncontext: MortonContext) extends Sink[Feature] {

  import scala.collection.mutable.Map

  private val GROUP_SIZE = 5000
  val mortoncode = new MortonCode(mortoncontext)

  private def beforeIn = collection.dropIndexes

  def in(iterator: Iterator[Feature]) {

    val mcStats = Map[String, Int]()

    def transform(f: Feature): Option[DBObject] = {
      try {
        val mc = mortoncode ofGeometry f.getGeometry
        mcStats.put(mc, (mcStats.getOrElse(mc, 0) + 1))
        Some(MongoDbFeature(f, mc))
      } catch {
        case ex: IllegalArgumentException => {
          Logger.warn(" Can't save feature with envelope " + f.getGeometry.getEnvelope.toString)
          None
        }
      }
    }

    //we call flatten to remove the None items generated when mapping toFeature
    val groupedObjIterator = (iterator.map(transform(_)).flatten) grouped GROUP_SIZE
    while (groupedObjIterator.hasNext) {
      val group = groupedObjIterator.next()
      collection.insert(group: _*)
    }
    afterIn(mcStats)
  }

  private def afterIn(stats: Map[String, Int]) {

    collection.ensureIndex(SpecialMongoProperties.MC)
    import MetadataIdentifiers._

    val metadata = MongoDBObject(
      CollectionField -> collection.getName(),
      IndexStatsField -> stats,
      ExtentField -> EnvelopeSerializer(mortoncontext.getExtent),
      IndexLevelField -> mortoncontext.getDepth

    )
    val selector = MongoDBObject("collection" -> collection.getName())

    collection.getDB().getCollection(MetadataIdentifiers.MetadataCollection)
      .update(selector, metadata, true, false, WriteConcern.Safe)
  }

}

trait MortonCodeQueryOptimizer {
  //the return type of the optimizer
  type QueryDocuments = List[DBObject]

  /**
   * Optimizes the window query, given the specified MortonCode
   * @param window
   * @return
   */
  def optimize(window: Envelope, mortoncode: MortonCode): QueryDocuments
}

trait SubdividingMCQueryOptimizer extends MortonCodeQueryOptimizer {

  def optimize(window: Envelope, mortoncode: MortonCode): QueryDocuments = {

    /*
    recursively divide the subquery to lowest level
     */
    def divide(mc: String): Set[String] = {
      if (!(window intersects (mortoncode envelopeOf mc))) Set()
      else if (mc.length == mortoncode.getMaxLength) Set(mc)
      else divide(mc + "0") ++ divide(mc + "1") ++ divide(mc + "2") ++ divide(mc + "3")
    }

    /*
    expand a set of mortoncodes to all mortoncodes of predecessors. i.e expand "00" to
    "", "0","00"
     */
    def expand(mcs: Set[String]): Set[String] = {
      Set("") ++ (for (mc <- mcs; i <- 0 to mc.length) yield mc.substring(0, i)).toSet
    }

    //maps the set of mortoncode strings to a list of querydocuments
    def toQueryDocuments(mcSet: Set[String]): QueryDocuments = {
      mcSet.map(mc => MongoDBObject(SpecialMongoProperties.MC -> mc)).toList
    }

    val mc = mortoncode ofEnvelope window
    val result = (divide _ andThen expand _ andThen toQueryDocuments _)(mc)
    Logger.debug(s"num. of queries for window ${window.toString}= ${result.size}")
    result
  }

}


class MongoDbSource(val collection: MongoCollection, val mortoncontext: MortonContext)
  extends Source[Feature]
  with WindowQueryable[Feature] {

  //we require a MortonCodeQueryOptimizer to be mixed in on instantiation
  this: MortonCodeQueryOptimizer =>

  val mortoncode = new MortonCode(mortoncontext)

  def out(): Iterator[Feature] = toFeatureIterator(collection.iterator)

  /**
   *
   * @param window the query window
   * @return
   * @throws IllegalArgumentException if Envelope does not fall within context of the mortoncode
   */
  def query(window: Envelope): Iterator[Feature] = {

    // this is an alternative strategy
    //TODO -- reuse this e.g. by grouping by N mortoncodes in qds and chaining the OR-queries.
    //    /*
    //    * chain a stream iterators into a a very lazy ChainedIterator
    //    */
    //    def chain(iters: Stream[Iterator[Feature]]) = new ChainedIterator(iters)
    //
    //    // Use a stream such that the mapping of a querydocument to a DBCursor happens lazily
    //    val qds = optimize(window, mortoncode).toStream
    //    //TODO -- clean up filtering, is now too convoluted
    //    chain(
    //      qds.map( qd => toFeatureIterator(collection.find(qd)).filter(f => window.intersects(f.envelope)))
    //    )
    val qds = optimize(window, mortoncode)
    val qListBuilder = MongoDBList.newBuilder
    qds.foreach(qd => qListBuilder += qd)
    val query = MongoDBObject("$or" -> qListBuilder.result)
    toFeatureIterator(collection.find(query))
      .filter(f => window.intersects(f.envelope))
  }

  private def toFeatureIterator(originalIterator: Iterator[DBObject]): Iterator[FeatureWithEnvelope] = {
    originalIterator.map(MongoDbFeature.toFeature(_)).flatten
  }
}

object MongoDbSource {

  def apply(collection: MongoCollection, mortoncontext: MortonContext): MongoDbSource =
    new MongoDbSource(collection, mortoncontext) with SubdividingMCQueryOptimizer

}

/**
 * Converts between features and DBObjects
 *
 * @author Karel Maesen, Geovise BVBA
 *         creation-date: 3/2/13
 */
object MongoDbFeature {

  val geometryEncoder = Wkb.newEncoder()
  val geometryDecoder = Wkb.newDecoder()
  val base64Encoder = new BASE64Encoder();
  val base64Decoder = new BASE64Decoder();


  def propertyMap(feature: Feature): Seq[(String, Any)] = {
    feature.getProperties.map(p => (p, feature.getProperty(p))).toSeq
  }

  def geometryProperties(feature: Feature, mortoncode: String): Seq[(String, Any)] = {
    val geom = feature.getGeometry
    val wkbBASE64 = base64Encoder.encode(geometryEncoder.encode(geom, ByteOrder.XDR).toByteArray)
    val bbox = EnvelopeSerializer(geom.getEnvelope)
    import SpecialMongoProperties._
    (WKB, wkbBASE64) ::(MC, mortoncode) ::(BBOX, bbox) :: Nil
  }

  def apply(feature: Feature, mortoncode: String) = {
    val elems = propertyMap(feature) ++ geometryProperties(feature, mortoncode)
    (MongoDBObject.newBuilder ++= elems).result()
  }

  def toFeature(obj: MongoDBObject): Option[DBObjectFeature] = {
    if (obj.get(SpecialMongoProperties.WKB) == None || obj.get(SpecialMongoProperties.BBOX) == None) return None
    Some(new DBObjectFeature(obj))
  }

  /**
   * A Feature implementation that adapts a MongoDBObject
   *
   * @param obj the adapted MongoDBObject
   */
  class DBObjectFeature(val obj: MongoDBObject) extends FeatureWithEnvelope {

    import SpecialMongoProperties._

    lazy private val geometry: Geometry = {
      val bytes = base64Decoder.decodeBuffer(obj.as[String](WKB))
      geometryDecoder.decode( ByteBuffer.from( bytes ) )
    }

    lazy private val propertyMap = obj filterKeys (!isSpecialMongoProperty(_))

    val envelope = EnvelopeSerializer.unapply(obj.as[String](BBOX)).getOrElse(geometry.getEnvelope)

    def hasProperty(propertyName: String, inclSpecial: Boolean): Boolean =
      (inclSpecial && ("id".equals(propertyName) || "geometry".equals(propertyName))) ||
        (propertyMap.keys.contains(propertyName))

    def getProperties: util.Collection[String] = propertyMap.keys

    def getProperty(propertyName: String): AnyRef =
      if (propertyName == null) throw new IllegalArgumentException("Null parameter passed.")
      else propertyMap.get(propertyName).getOrElse(null) //when None, we return null to conform to the interface definition

    def getId: AnyRef = obj.getOrElse(ID, obj.getOrElse(_ID, "<no id.>"))

    def getGeometry: Geometry = geometry

    def getGeometryName: String = "geometry"

    def hasId: Boolean = true

    def hasGeometry: Boolean = true

  }

}


case class Metadata(envelope: Envelope, stats: Map[String, Int], name: String, level: Int)

object Metadata {

  import MetadataIdentifiers._

  def from(dbObj: DBObject): Option[Metadata] = {
    val envOpt: Option[Envelope] = dbObj.getAs[String](ExtentField) match {
      case Some(EnvelopeSerializer(env)) => Some(env)
      case _ => None
    }
    try {
      val nameOpt: Option[String] = dbObj.getAs[String](CollectionField)
      val indexOpt: Option[Map[String, Int]] = toMap(dbObj.getAs[DBObject](IndexStatsField))
      val levelOpt: Option[Int] = dbObj.getAs[Int](IndexLevelField)
      (envOpt, nameOpt, indexOpt, levelOpt) match {
        case (Some(env), Some(name), Some(index), Some(level)) => Some(Metadata(env, index, name, level))
        case _ => None
      }
    } catch {
      case _: Throwable => None
    }
  }

  private def toMap(optObj: Option[DBObject]): Option[Map[String, Int]] = {
    for (obj <- optObj; m <- dbObjtoMap(obj)) yield m
  }

  private def dbObjtoMap(obj: DBObject): Option[Map[String, Int]] = {
    try {
      val m: Map[String, Int] = (for ((k, v) <- obj) yield (k.asInstanceOf[String], v.asInstanceOf[Int])).toMap
      Some(m)
    } catch {
      case _: Throwable => None
    }
  }

}


object EnvelopeSerializer {

  private val pattern = "(\\d\\d\\d\\d):(-*[\\.\\d]+),(-*[\\.\\d]+),(-*[\\.\\d]+),(-*[\\.\\d]+)".r

  def apply(bbox: Envelope): String = {
    if (bbox == null) ""
    else {
      val srid = bbox.getCrsId.getCode
      val builder = new StringBuilder()
        .append(srid)
        .append(":")
        .append(List(bbox.getMinX, bbox.getMinY, bbox.getMaxX, bbox.getMaxY).mkString(","))
      builder.toString
    }
  }

  def unapply(str: String): Option[Envelope] =
    cleaned(str) match {
      case pattern(srid, minx, miny, maxx, maxy) => {
        try {
          Some(new Envelope(minx.toDouble, miny.toDouble, maxx.toDouble, maxy.toDouble, CrsId.parse(srid)))
        } catch {
          case _: Throwable => None
        }
      }
      case _ => None
    }

  def cleaned(str: String): String = str.trim

}
