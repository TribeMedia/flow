package finatra.service

import finatra.data.DataProvider
import org.apache.spark.mllib.recommendation.Rating

/**
  * Created by logicalguess on 2/28/16.
  */
trait RecommenderService {
  def dataProvider: DataProvider

  def getRecommendationsForUser(userId: Int, count: Int): (Seq[Rating], Double, String, String)

  def getItems(itemIds: List[Int]): List[String] = {
    val products = dataProvider.getProductNames()
    itemIds.map { id => products(id)}
  }
}
