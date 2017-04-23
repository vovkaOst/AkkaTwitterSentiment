import java.io.StringReader
import scala.collection.JavaConverters._

import edu.stanford.nlp.util.StringUtils


class DocumentPreprocessor(val ngramOrder: Int) {
  def getTermsFromDocument(document: String): List[String] = {
    val coreNlpPreprocessor = new edu.stanford.nlp.process.DocumentPreprocessor(new StringReader(document))
    for {
      sentence <- coreNlpPreprocessor.asScala.toList
      words = sentence.asScala.map(_.word()).toList
      ngram <- StringUtils.getNgrams(words.asJava, ngramOrder, ngramOrder).asScala
    } yield ngram
  }
}