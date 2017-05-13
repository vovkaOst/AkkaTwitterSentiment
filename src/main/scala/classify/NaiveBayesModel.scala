package classify

import scala.collection.mutable
import scala.math.log
import akka.actor._
import akka.pattern.ask
import akka.util.Timeout

import scala.concurrent.Await
import scala.concurrent.duration._


case class DocumentCategoryMessage(document: String, category: String)
case class ClassifyDocumentMessage(document: String, sendCategoryTo: ActorRef, data: Any)
case class CategoryMessage(category: Option[String], data: Any)
case class SetNGramOrder(ngramOrder: Int)
case class SetWorkersNumber(workers: Int)


class NaiveBayesModelRouterActor(categoriesRepositoryActor: ActorRef) extends Actor {
  private val naiveBayesModelActors = mutable.ListBuffer[ActorRef]()
  var nextWorkerIndex = 0

  def selectWorker() = {
    val nextWorker = naiveBayesModelActors(nextWorkerIndex)
    nextWorkerIndex += 1
    nextWorkerIndex %= naiveBayesModelActors.size
    nextWorker
  }

  override def receive = {
    case SetWorkersNumber(workers) =>
      val delta = workers - naiveBayesModelActors.size
      nextWorkerIndex %= workers
      val createOrDestroyMessage = if (delta > 0) CreateNaiveBayesModelActor else DestroyNaiveBayesModelActor
      for (_ <- 1 to math.abs(delta)) {
        categoriesRepositoryActor ! createOrDestroyMessage
      }

    case ModelActorCreated(actorRef) =>
      naiveBayesModelActors += actorRef

    case ModelActorDestroyed(actorRef) =>
      naiveBayesModelActors -= actorRef

    case message@DocumentCategoryMessage(_, _) =>
      selectWorker() ! message

    case message@ClassifyDocumentMessage(_, _, _) =>
      selectWorker() ! message
  }
}


class NaiveBayesModelActor(catgoriesRepositoryActor: ActorRef) extends Actor {

  val categoryActors: mutable.Map[String, ActorRef] = mutable.Map[String, ActorRef]()
  var documentPreprocessor = new DocumentPreprocessor(2)

  override def receive = {
    case NewCategory(category, categoryActor) =>
      categoryActors(category) = categoryActor
    case DocumentCategoryMessage(document, category) =>
      addDocument(document, category)
    case ClearTrainedModel =>
      categoryActors.clear()
    case SetNGramOrder(ngramOrder) =>
      documentPreprocessor = new DocumentPreprocessor(ngramOrder)
    case ClassifyDocumentMessage(document, sendCategoryTo, data) =>
      val category = if (categoryActors.isEmpty) None else Some(classifyDocument(document))
        sendCategoryTo ! CategoryMessage(category, data)
  }

  def addDocument(document: String, category: String): Unit = {
    implicit val duration: Timeout = 20 seconds;
    if (!categoryActors.contains(category)) {
      val categoryAgentFuture = catgoriesRepositoryActor ? GetCategoryActor(category)
      categoryActors(category) = Await.result(categoryAgentFuture, 10 seconds).asInstanceOf[ActorRef]
    }
    categoryActors(category) ! AddDocumentMessage(document)
  }

  def classifyDocument(document: String): String = {
    implicit val duration: Timeout = 20 seconds;
    val allNGrams = documentPreprocessor.getTermsFromDocument(document)
    val documentsCount = categoryActors.mapValues((actor) => Await.result(actor ? GetDocumentsCount, 20 seconds).asInstanceOf[Int])
    val allDocumentsCount: Double = documentsCount.values.sum
    val ngramLikelihoods: List[(String, Double)] = for {
      (category, categoryActor) <- categoryActors.toList
      ngram <- allNGrams
      ngramProbability = Await.result(categoryActor ? GetNGramProbablityMessage(ngram), 10 seconds).asInstanceOf[Double]
    } yield (category, log(ngramProbability))
    val scoredCategories = ngramLikelihoods.groupBy(_._1).mapValues(_.map(_._2).sum).map((keyVal) => (keyVal._1, keyVal._2 + log(documentsCount(keyVal._1) / allDocumentsCount)))
    if (scoredCategories.isEmpty) {
      ""
    }
    else {
      scoredCategories.maxBy(_._2)._1
    }
  }
}
