package persistence.postgresql

import persistence.querylang._

/**
 * Created by Karel Maesen, Geovise BVBA on 16/04/16.
 */
object PGRegularQueryRenderer extends BaseQueryRenderer {

  protected def renderPropertyExpr(lhs: PropertyExpr): String =
    if (lhs.path.trim.startsWith("properties.")) lhs.path.trim.substring(11)
    else lhs.path

  override def renderBooleanAnd(
    lhs: BooleanExpr,
    rhs: BooleanExpr
  ): String = s" ( ${render(lhs)} ) AND ( ${render(rhs)} )"

  override def renderBooleanOr(
    lhs: BooleanExpr,
    rhs: BooleanExpr
  ): String = s" ( ${render(lhs)} ) OR ( ${render(rhs)} )"

  override def renderBooleanNot(inner: BooleanExpr): String = s" NOT ( ${render(inner)} ) "

  override def renderComparisonPredicate(
    lhs: PropertyExpr,
    op: ComparisonOperator,
    rhs: ValueExpr
  ): String = s" ${renderPropertyExpr(lhs)} ${sym(op)} ( ${renderValue(rhs)} )"

  override def renderInPredicate(
    lhs: PropertyExpr,
    rhs: ValueListExpr
  ): String = s" ${renderPropertyExpr(lhs)} in ${renderValueList(rhs)}"

  override def renderRegexPredicate(
    lhs: PropertyExpr,
    rhs: RegexExpr
  ): String = s" ${renderPropertyExpr(lhs)} ~ '${rhs.pattern}'"

  override def renderLikePredicate(
    lhs: PropertyExpr,
    rhs: LikeExpr
  ): String = s" ${renderPropertyExpr(lhs)} ilike '${rhs.pattern}'"

  override def renderNullTestPredicate(
    lhs: PropertyExpr,
    is: Boolean
  ): String = s" ${renderPropertyExpr(lhs)} ${if (is) "is" else "is not"} null"
}
