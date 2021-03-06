package download

import java.io.{File, FileOutputStream, PrintWriter}
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.time.LocalDate
import java.util.Locale

import akka.pattern.ask

import scala.concurrent.duration._
import akka.actor.{Props, PoisonPill, Actor, ActorRef}
import akka.util.Timeout
import classify.{NaiveBayesModelActor, CategoryMessage, ClassifyDocumentMessage, DocumentCategoryMessage}
import oauth.signpost.commonshttp.CommonsHttpOAuthConsumer
import org.apache.commons.io.IOUtils
import org.apache.http.client.methods.HttpGet
import org.apache.http.impl.client.DefaultHttpClient
import org.json4s.DefaultFormats
import play.api.libs.json.Json

import scala.collection.JavaConverters._
import scala.collection.mutable
import scala.collection.immutable
import scala.collection.mutable.Map
import scala.concurrent.Await
import scala.util.parsing.json.JSON

/**
  * Created by ostruk on 4/25/17.
  */
case object GetDateToStatMessage
case class SetUserKeysDownloader(ck: String, cs: String, at: String, as: String)
case class SetRange(from: LocalDate, To: LocalDate)
case class AnalyseTweetsForHashtag(ht:String)
case class CountCategoryMessage(c:String, dm:String)
case object DoneAnalysing

class RangeDownloaderRouterActor (routerActor: ActorRef, GuiActor: ActorRef) extends Actor {

  var RangeDownloadActors = mutable.Set[ActorRef]()
  var DateToStat = scala.collection.mutable.Map[String, Map[String, Int]]()
  var DoneAn =0
  override def receive = {
    case SetRange(f, t) =>
      //uwaga - nie zadziala dla konca roku
    {
      var from = f
      RangeDownloadActors.foreach(a => a ! PoisonPill)
      RangeDownloadActors.clear()
      while (!from.isAfter(t))
        {
          val newActor = context.actorOf(Props(new TweetDatesRangeDownloader(routerActor, self)))
          newActor ! SetRange(from, from.plusDays(1))
          RangeDownloadActors.add(newActor)
          from = from.plusDays(1)
        }
    }

    case message@AnalyseTweetsForHashtag(_) => {
      DateToStat = scala.collection.mutable.Map[String, Map[String, Int]]()
      DoneAn =0
      RangeDownloadActors.foreach(_ ! message)
    }
    case SetUserKeysDownloader(ck: String, cs: String, at: String, as: String) => {
      for (ac <- RangeDownloadActors)
        ac ! SetUserKeysDownloader(ck, cs, at, as)
    }
    case DoneAnalysing => {
      DoneAn+=1
      if(DoneAn==RangeDownloadActors.size) GuiActor ! DoneAnalysing
    }

    case GetDateToStatMessage =>
      sender ! DateToStat
    case CountCategoryMessage(c: String, dayMonth:String) =>
      if(c != null) {
        if(!DateToStat.contains(c))
        {DateToStat(c) = Map[String, Int](); println("Set new emoji to map!!###") }
        //if sentiment didn't exist in map will be created now
        if(!DateToStat(c).contains(dayMonth))
          DateToStat(c)(dayMonth) = 1
        else DateToStat(c)( dayMonth) = DateToStat(c)( dayMonth) + 1
      }
      else println("Ccategory is null wtf??")
  }
}

class TweetDatesRangeDownloader(naiveBayesActor: ActorRef, myRouterActor: ActorRef) extends Actor {

  var consumer: CommonsHttpOAuthConsumer = null
  var FromRange: String = null
  var ToRange: String = null
 // consumer.setTokenWithSecret(AccessToken, AccessSecret);

  class CC[T] {
    def unapply(a: Any): Option[T] = Some(a.asInstanceOf[T])
  }

  object M extends CC[immutable.Map[String, Any]]
  object L extends CC[List[Any]]
  object S extends CC[String]
  object D extends CC[Double]
  object B extends CC[Boolean]


  def search(num: Int, query: String) {

    if (num < 1) {
      return
    }
    //consumer.setTokenWithSecret(AccessToken, AccessSecret);
    val request = new HttpGet("https://api.twitter.com/1.1/search/tweets.json" + query);
    consumer.sign(request);

    val client = new DefaultHttpClient();
    val response = client.execute(request);

    val jsonRes = IOUtils.toString(response.getEntity().getContent())
//    new PrintWriter("JsonResult_" + num + ".txt") {
//      write(jsonRes); close
//    }
    //myparse(jsonRes, "onlyTweets_"+num+".txt");


    val bString = jsonRes.replaceAll("[\\t\\n\\r]+", " ");
    val result = for {
      Some(M(map)) <- List(JSON.parseFull(bString))
      L(statuses) = map("statuses")
      M(tweet) <- statuses
      S(text) = tweet("text")
      S(created) = tweet("created_at")
      D(id) = tweet("id")
    } yield {
      (text.replaceAll("[\\t\\n\\r]+", " "), created, id)
    }
    //implicit val timeout = Timeout(400 seconds)
    //println(result.size)

    //CAN PRINT parsed Tweets to file
   // val st = result.map { tuple => tuple.productIterator.mkString("\t") }
   // new PrintWriter("textTweets.txt") {
   //   write(st mkString ("\n")); close
   // }

    //!!!!!!!or can send for classification
    //val DatesTweetsCategories = result.map { tuple =>   (tuple._2, tuple._1, (Await.result(naiveBayesActor ? ClassifyDocumentMessage(tuple._1, self, None), timeout.duration).asInstanceOf[CategoryMessage]).category.get.toString())}
    result.foreach { tuple => naiveBayesActor ! ClassifyDocumentMessage(tuple._1, self, (tuple._2, tuple._1))}

    implicit val formats = DefaultFormats
    val parsedJson = Json.parse(jsonRes.toString)
    try
    {
       val value1 = parsedJson \ "search_metadata" \ "next_results"

      println (value1.as[String])//.map(_.as[String]).lift(1))

      search(num - 1, value1.as[String])
    }
    catch
      {
        case _: Throwable => println("No more values for those days!")
      }

  }

  def encodeFirstQuery(keywords: String, dateFrom: String, dateTo: String): String = {
    var sinceStr = ""
    var untilStr = ""

    if (dateFrom != "") sinceStr = " since:" + dateFrom
    if (dateTo != "") untilStr = " until:" + dateTo

    val str = keywords + sinceStr + untilStr;
    val s = URLEncoder.encode(str, "UTF-8");
    val query = "?q=" + s + "&count=100&lang=en";
    println(s)
    query
  }

  def receive = {
    case SetRange(dfr, dto) => {
      FromRange = dfr.getYear().toString()+"-"+dfr.getMonthValue().toString()+"-"+dfr.getDayOfMonth().toString()
      ToRange = dto.getYear().toString()+"-"+dto.getMonthValue().toString()+"-"+dto.getDayOfMonth().toString()
    }
    case AnalyseTweetsForHashtag(q: String) => {
      search(4, encodeFirstQuery(q, FromRange, ToRange))
      sender ! DoneAnalysing
    }

    case CategoryMessage(category: Option[String], dateTweet: (String, String)) if category.isDefined => {

      val TWITTER = "EEE MMM dd HH:mm:ss ZZZZZ yyyy";
      val sf = new SimpleDateFormat(TWITTER, Locale.US);

        val c = category.get
        val d = dateTweet._1
        val t = dateTweet._2

        val date = sf.parse(d.toString())
        val dayMonth:String = date.getYear().toString() +"-"+ date.getMonth().toString()+"-"+ date.getDay().toString()

      myRouterActor ! CountCategoryMessage(c, dayMonth)

    }
    case SetUserKeysDownloader(ck: String, cs: String, at: String, as: String) =>
      consumer = new CommonsHttpOAuthConsumer(ck, cs)
      consumer.setTokenWithSecret(at, as)
  }
}


