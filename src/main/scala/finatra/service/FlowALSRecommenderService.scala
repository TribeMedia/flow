package finatra.service

import javax.inject.{Inject, Singleton}

import dag.{Connector, DAG, Node, Util}
import finatra.data.DataProvider
import flow.OperationBuilder
import flow.OperationImplicits._
import org.apache.spark.SparkContext
import org.apache.spark.mllib.recommendation.{ALS, MatrixFactorizationModel, Rating}
import org.apache.spark.rdd.RDD

import scala.util.Random

/**
  * Created by logicalguess on 2/26/16.
  */

@Singleton
case class FlowALSRecommenderService @Inject()(sc: SparkContext, dataProvider: DataProvider) extends RecommenderService {

  val model: MatrixFactorizationModel = createModel

  def getRecommendationsForUser(userId: Int, count: Int) = {

    val userIdFn = {userId}

    val sparkContextFn = {sc}

    val productsFn = dataProvider.getProductNames

    val candidatesFn: ((SparkContext, Map[Int, String])) => RDD[Int] = t => t match {
      case (sc: SparkContext, products: Map[Int, String]) => sc.parallelize(products.keys.toSeq)
    }

    val mapByIdFn: ((Int, RDD[Int])) => RDD[(Int, Int)] =  t => t match {
      case (userId: Int, rdd: RDD[Int]) => rdd.map((userId, _))
    }

    val predictFn = { rdd: RDD[(Int, Int)] =>
      model
        .predict(rdd)
        .collect
        .sortBy(- _.rating)
        .take(count)
    }

    val fns = predictFn(mapByIdFn(userId, candidatesFn(sparkContextFn, productsFn)))

    val n0 = Node("userId")
    val n1 = Node("products")
    val n2 = Node("candidates")
    val n3 = Node("mapById")
    val n4 = Node("predict")
    val n5 = Node("sc")


    val c1 = Connector("sc", "candidates")
    val c2 = Connector("products", "candidates")
    val c3 = Connector("userId", "mapById")
    val c4 = Connector("candidates", "mapById")
    val c5 = Connector("mapById", "predict")


    val graph = DAG("flow", List(n0, n1, n2, n3, n4, n5), List(c1, c2, c3, c4, c5))

    val ops = OperationBuilder(graph,
      Map("candidates" -> candidatesFn, "mapById" -> mapByIdFn, "predict" -> predictFn),
      Map("sc" -> sparkContextFn, "userId" -> userIdFn, "products" -> productsFn))

    (ops("predict")().asInstanceOf[Array[Rating]].toList ++ fns, Util.gravizoDotLink(DAG.dotFormatDiagram(graph)))

  }

  def createModel: MatrixFactorizationModel = {
    val rand = new Random()
    val ratings: RDD[(Long, Rating)] = dataProvider.getRatings().map { r => (rand.nextInt(10).toLong, r)}
    val numRatings = ratings.count
    // _._2 is the RDD ratings's Rating in the (Int, Rating) pairs
    // The Rating class is a wrapper around tuple (user: Int, product: Int, rating: Double)
    val numUsers = ratings.map(_._2.user).distinct.count
    val numMovies = ratings.map(_._2.product).distinct.count

    println("Loaded data: " + numRatings + " ratings from "
      + numUsers + " users on " + numMovies + " movies.")

    // We will use MLlib’s ALS to train a MatrixFactorizationModel,
    // which takes a RDD[Rating] object as input.
    // ALS has training parameters such as rank for matrix factors and regularization constants.
    // To determine a good combination of the training parameters,
    // we split ratings into train (60%), validation (20%), and test (20%) based on the
    // last digit of the timestamp, and cache them

    val numPartitions = 20
    // ratings format // format: (timestamp % 10, Rating(userId, movieId, rating))
    // The training set is 60%, it is based on the last digit of the timestamp
    // change to 30%, 10% and 10%
    val training = ratings.filter(x => x._1 <= 3)
      .values
      .repartition(numPartitions)
      .persist
    // val validation = ratings.filter(x => x._1 >= 3 && x._1 < 8)
    val validation = ratings.filter(x => x._1 == 4 )
      .values
      .repartition(numPartitions)
      .persist
    // val test = ratings.filter(x => x._1 >= 8).values.persist
    val test = ratings.filter(x => x._1 == 5).values.persist

    val numTraining = training.count
    val numValidation = validation.count
    val numTest = test.count

    println("\nStep 2, train with " + numTraining + " ratings.")
    // println("\nTraining: " + numTraining + " ratings, validation: " + numValidation + " ratings, test: " + numTest + " ratings.")

    // train models and evaluate them on the validation set
    // we will test only 8 combinations resulting from the cross product of 2 different ranks (8 and 12)
    // use rank 12 to reduce the running time
    // val ranks = List(8, 12)
    val ranks = List(12)

    // 2 different lambdas (1.0 and 10.0)
    val lambdas = List(0.1, 10.0)

    // two different numbers of iterations (10 and 20)
    // use numIters 20 to reduce the running time
    // val numIters = List(10, 20)
    val numIters = List(10)

    // We use the provided method computeRmse to compute the RMSE on the validation set for each model.
    // The model with the smallest RMSE on the validation set becomes the one selected
    // and its RMSE on the test set is used as the final metric
    // import org.apache.spark.mllib.recommendation.MatrixFactorizationModel
    var bestModel: Option[MatrixFactorizationModel] = None
    var bestValidationRmse = Double.MaxValue
    var bestRank = 0
    var bestLambda = -1.0
    var bestNumIter = -1
    for (rank <- ranks; lambda <- lambdas; numIter <- numIters) {
      // in object ALS
      // def train(ratings: RDD[Rating], rank: Int, iterations: Int, lambda: Double) : MatrixFactorizationModel
      val model = ALS.train(training, rank, numIter, lambda)

      // def computeRmse(model: MatrixFactorizationModel, data: RDD[Rating], n: Long)
      // return  math.sqrt, type is double
      // model is from training.
      val validationRmse = computeRmse(model, validation, numValidation)
      // println("RMSE (validation) = " + validationRmse + " for the model trained with rank = "
      //    + rank + ", lambda = " + lambda + ", and numIter = " + numIter + ".")
      if (validationRmse < bestValidationRmse) {
        // println("inside bestModel  " +  bestModel);
        bestModel = Some(model)
        bestValidationRmse = validationRmse
        bestRank = rank
        bestLambda = lambda
        bestNumIter = numIter
      }
    }

    // evaluate the best model on the test set
    println("\nStep 3, evaluate the best model on the test set.")

    val testRmse = computeRmse(bestModel.get, test, numTest)

    println("The best model was trained with rank = " + bestRank + " and lambda = " + bestLambda
      + ", and numIter = " + bestNumIter + ", and its RMSE on the test set is " + testRmse + ".")
    bestModel.get
  }

  /** Compute RMSE (Root Mean Squared Error). */
  def computeRmse(model: MatrixFactorizationModel, data: RDD[Rating], n: Long) = {
    val predictions: RDD[Rating] = model.predict(data.map(x => (x.user, x.product)))
    val predictionsAndRatings = predictions.map(x => ((x.user, x.product), x.rating))
      .join(data.map(x => ((x.user, x.product), x.rating)))
      .values
    math.sqrt(predictionsAndRatings.map(x => (x._1 - x._2) * (x._1 - x._2)).reduce(_ + _) / n)
  }


}