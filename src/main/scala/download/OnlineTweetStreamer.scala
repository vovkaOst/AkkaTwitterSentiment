package download

import java.io.{File, FileOutputStream, PrintWriter}

import com.danielasfregola.twitter4s.TwitterStreamingClient
import com.danielasfregola.twitter4s.entities.Tweet
import com.danielasfregola.twitter4s.entities.streaming.common.WarningMessage
import com.danielasfregola.twitter4s.entities.streaming.StreamingMessage
import com.danielasfregola.twitter4s.entities.{AccessToken, ConsumerToken}
import com.danielasfregola.twitter4s.entities.enums.Language
import classify.DocumentCategoryMessage
import akka.actor.{PoisonPill, Props, ActorRef, Actor}
import com.danielasfregola.twitter4s.http.clients.streaming.TwitterStream

import scala.collection.mutable
import scala.collection.immutable
import scala.collection.mutable.Map
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

case class StartStreamingMessage(emoList : List[String], GEmoMap : immutable.Map[String, immutable.Set[String]])
case object StopStreamingMessage
case object UpdateCountReceivedNum
case class AskAboutTweetsProcessedMessage()
case class SetUserKeys(ct: ConsumerToken, at: AccessToken)
case class SetStreamersForKeys(m: mutable.Set[(ConsumerToken, AccessToken)])

class OnlineStreamerRouterActor (routerActor: ActorRef) extends Actor {

  var GlobalEmojiMap = immutable.Map("happiness" -> immutable.Set("😀"),  "surprise" -> immutable.Set("😯"), "sadness"  -> immutable.Set("☹️"),  "anger" ->  immutable.Set("😠"), "disgust" -> immutable.Set("\uD83D\uDE12") ,  "fear"  -> immutable.Set("\uD83D\uDE31"))
  var StreamActors = mutable.Set[ActorRef]()
  var DateToStat = scala.collection.mutable.Map[String, Map[String, Int]]()
  var countReceived = 0
  override def receive = {
    case SetStreamersForKeys(m ) =>
    {
      StreamActors.foreach(a => a ! PoisonPill)
      StreamActors.clear()
      m.foreach(cat =>{
        val newActor = context.actorOf(Props(new OnlineTweetStreamer(cat._1, cat._2, routerActor)))
        StreamActors.add(newActor)
      })
    }

    case StartStreamingMessage(emoList : List[String], emomap) => {
      //no more actors than emotions in emoList will be used
      val emojiNumForActor = emoList.size / StreamActors.size
      var emojiIndex = 0
       if(emojiNumForActor>0)
         {
           StreamActors.foreach(a =>
           if(emojiIndex+emojiNumForActor<=emoList.size)
             {
               a ! StartStreamingMessage(emoList.slice(emojiIndex, emojiIndex+emojiNumForActor), emomap)
               emojiIndex += emojiNumForActor
             }
           else if(emojiIndex<emoList.size){
             a ! StartStreamingMessage(emoList.slice(emojiIndex, emoList.size), emomap)
             emojiIndex = emoList.size
           }

           )
         }
       else {
         //more keys than emojis
         StreamActors.foreach(a => if(emojiIndex<emoList.size) a ! StartStreamingMessage(emoList.slice(emojiIndex, emojiIndex+1), emomap))
       }

    }
    case message@StopStreamingMessage => {
      StreamActors.foreach(_ ! message)
    }
    case AskAboutTweetsProcessedMessage => sender ! countReceived
    case UpdateCountReceivedNum => {
      countReceived = 0
      StreamActors.foreach(_ ! AskAboutTweetsProcessedMessage)
    }
    case received:Int => countReceived+=received
  }
}


class OnlineTweetStreamer(consumerToken: ConsumerToken, accessToken: AccessToken, receiver: ActorRef) extends Actor {

  var GlobalEmojiMap = immutable.Map("happiness" -> immutable.Set("😀"),  "surprise" -> immutable.Set("😯"), "sadness"  -> immutable.Set("☹️"),  "anger" ->  immutable.Set("😠"), "disgust" -> immutable.Set("\uD83D\uDE12") ,  "fear"  -> immutable.Set("\uD83D\uDE31"))

  var AgentEmotionsList : List[String] = "happiness" :: "surprise" :: "sadness" :: "anger" :: "disgust" :: "fear" :: Nil
  var client = TwitterStreamingClient(consumerToken, accessToken)
  var countReceived = 0
  var emojiListToStream: List[String] = Nil
  var actorOnHold = true
  var streamerFutureOption: Option[Future[TwitterStream]] = None

  def filterEmoji(t : String): String = {
    val globalemojiList : List[String] = GlobalEmojiMap.values.flatten.toList
    val emoCheck : mutable.Map[String, Int] = mutable.Map(globalemojiList map { s => (s, 0)} : _*)
    globalemojiList.foreach(elem => if(t.contains(elem)) emoCheck(elem) = emoCheck(elem)+1)

    var rv = "Many"
    val foundEmoji:List[String] = emoCheck.filter(t => t._2>0).keys.toList

    var reverseHelper =  mutable.Map[String, String]()
    GlobalEmojiMap.foreach(el => el._2.foreach(listel => reverseHelper(listel) = el._1))
    var foundEmotions = mutable.Map(GlobalEmojiMap.keys.toList map { s => (s, 0)} : _*)

    foundEmoji.foreach(elem => { foundEmotions(reverseHelper(elem)) = foundEmotions(reverseHelper(elem)) + 1 })

    foundEmotions.values.count(_>0) match {
      case 1 => for((e, c) <- foundEmotions) {if(c>0) rv = e }
      case 0 => rv = "None"
      case _ => rv = "Many"
    }
     rv
  }

  def sendTweetText: PartialFunction[StreamingMessage, Unit] = {
    case tweet: Tweet => {
      if (!actorOnHold) {
        val emoji = filterEmoji(tweet.text)
        if (emoji != "None" && emoji != "Many") {
          receiver ! DocumentCategoryMessage(tweet.text.filter(_ >= ' '), emoji)
         // new PrintWriter(new FileOutputStream(new File("Day2TweetsFromStreamer.txt"),true))
         // { write(emoji+"\t"+tweet.text.filter(_ >= ' ').replace('\n', ' ').replace('\t', ' ')+"\n"); close }
           countReceived = countReceived + 1
        }
      }
    }
    case warning: WarningMessage => println("koniec bo blad " + warning.toString)
  }



  def receive = {
    case StartStreamingMessage(emoList : List[String], emomap) => {
      AgentEmotionsList = emoList
      GlobalEmojiMap = emomap
      emoList.foreach(
        emo => {
          if(GlobalEmojiMap.contains(emo)) {
            emojiListToStream = emojiListToStream ::: GlobalEmojiMap(emo).toList
            AgentEmotionsList :+ emo
          }
        }
      )

      actorOnHold = false
      streamerFutureOption = Some(client.filterStatuses(tracks = emojiListToStream, languages = List(Language.English))(sendTweetText))
    }
    case StopStreamingMessage =>
      streamerFutureOption match {
        case None => Unit
        case Some(streamerFuture) =>
          streamerFuture.foreach(_.close())
      }
      streamerFutureOption = None
    case AskAboutTweetsProcessedMessage() =>
      sender ! countReceived
    case SetUserKeys(ct: ConsumerToken, at: AccessToken) =>
      client = TwitterStreamingClient(ct, at)

  }

}
