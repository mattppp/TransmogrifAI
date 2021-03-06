/*
 * Copyright (c) 2017, Salesforce.com, Inc.
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * * Redistributions of source code must retain the above copyright notice, this
 *   list of conditions and the following disclaimer.
 *
 * * Redistributions in binary form must reproduce the above copyright notice,
 *   this list of conditions and the following disclaimer in the documentation
 *   and/or other materials provided with the distribution.
 *
 * * Neither the name of the copyright holder nor the names of its
 *   contributors may be used to endorse or promote products derived from
 *   this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS"
 * AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE
 * IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE
 * FOR ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL
 * DAMAGES (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR
 * SERVICES; LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER
 * CAUSED AND ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY,
 * OR TORT (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
 * OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package com.salesforce.op.dsl

import java.util.regex.Pattern

import com.salesforce.op.features.FeatureLike
import com.salesforce.op.features.types._
import com.salesforce.op.stages.impl.feature._
import com.salesforce.op.utils.text._
import org.apache.lucene.analysis.Analyzer
import org.apache.lucene.analysis.Analyzer.TokenStreamComponents
import org.apache.lucene.analysis.pattern.PatternTokenizer

import scala.reflect.runtime.universe.TypeTag
import scala.util.Try


trait RichTextFeature {
  self: RichFeature =>

  implicit class RichTextFeature[T <: Text : TypeTag](val f: FeatureLike[T])(implicit val ttiv: TypeTag[T#Value]) {

    /**
     * Convert this Text feature into a MultiPickList feature, whose category is a one-element set of this
     * Text's value.
     *
     * @return A new MultiPickList feature
     */
    def toMultiPickList: FeatureLike[MultiPickList] = f.map[MultiPickList](_.value.toSet[String].toMultiPickList)


    /**
     * Converts a sequence of Text features into a vector keeping the top K most common occurrences of each
     * Text feature (ie the final vector has length k * number of Text inputs). Plus two additional columns
     * for "other" values and nulls - which will capture values that do not make the cut or values not seen in training
     *
     * @param others     other features to include in the pivot
     * @param topK       keep topK values
     * @param minSupport Min times a value must occur to be retained in pivot
     * @param cleanText  if true ignores capitalization and punctuations when grouping categories
     * @param trackNulls keep an extra column that indicated if feature was null
     * @return
     */
    def pivot
    (
      others: Array[FeatureLike[T]] = Array.empty,
      topK: Int = TransmogrifierDefaults.TopK,
      minSupport: Int = TransmogrifierDefaults.MinSupport,
      cleanText: Boolean = TransmogrifierDefaults.CleanText,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls
    ): FeatureLike[OPVector] = {
      val vectorizer = new OpTextPivotVectorizer[T]()

      f.transformWith[OPVector](
        stage = vectorizer.setTopK(topK).setMinSupport(minSupport).setCleanText(cleanText).setTrackNulls(trackNulls),
        fs = others
      )
    }


    /**
     * Apply N-gram Similarity transformer
     *
     * @param that      other text feature
     * @param nGramSize the size of the n-gram to be used to compute the string distance
     * @return ngrammed feature
     */
    def toNGramSimilarity(that: FeatureLike[T], nGramSize: Int = NGramSimilarity.nGramSize): FeatureLike[RealNN] =
      f.transformWith(new TextNGramSimilarity[T](nGramSize), that)

    /**
     * Vectorize text features by first tokenizing each using [[TextTokenizer]] and then
     * applying [[OPCollectionHashingVectorizer]].
     *
     * @param others               other text features to vectorize with the parent feature
     * @param languageDetector     a language detector instance
     * @param analyzer             a text analyzer instance
     * @param autoDetectLanguage   indicates whether to attempt language detection
     * @param defaultLanguage      default language to assume in case autoDetectLanguage is disabled or
     *                             failed to make a good enough prediction.
     * @param autoDetectThreshold  Language detection threshold. If none of the detected languages have
     *                             confidence greater than the threshold then defaultLanguage is used.
     * @param hashSpaceStrategy    strategy to determine whether to use shared hash space for all included features
     * @param minTokenLength       minimum token length, >= 1.
     * @param trackNulls           indicates whether or not to track null values in a separate column.
     *                             Since features may be combined into a shared hash space here, the null value
     *                             should be tracked separately
     * @param toLowercase          indicates whether to convert all characters to lowercase before analyzing
     * @param numHashes            number of features (hashes) to generate
     * @param hashWithIndex        include indices when hashing a feature that has them (OPLists or OPVectors)
     * @param prependFeatureName   if true, prepends a input feature name to each token of that feature
     * @param hashAlgorithm        hash algorithm to use
     * @param binaryFreq           if true, term frequency vector will be binary such that non-zero term
     *                             counts will be set to 1.0
     * @return result feature of type Vector
     */
    // scalastyle:off parameter.number
    def vectorize
    (
      numHashes: Int,
      autoDetectLanguage: Boolean,
      minTokenLength: Int,
      toLowercase: Boolean,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls,
      hashWithIndex: Boolean = TransmogrifierDefaults.HashWithIndex,
      binaryFreq: Boolean = TransmogrifierDefaults.BinaryFreq,
      prependFeatureName: Boolean = TransmogrifierDefaults.PrependFeatureName,
      autoDetectThreshold: Double = TextTokenizer.AutoDetectThreshold,
      hashSpaceStrategy: HashSpaceStrategy = TransmogrifierDefaults.HashSpaceStrategy,
      defaultLanguage: Language = TextTokenizer.DefaultLanguage,
      hashAlgorithm: HashAlgorithm = TransmogrifierDefaults.HashAlgorithm,
      languageDetector: LanguageDetector = TextTokenizer.LanguageDetector,
      analyzer: TextAnalyzer = TextTokenizer.Analyzer,
      others: Array[FeatureLike[T]] = Array.empty
    ): FeatureLike[OPVector] = {
      // scalastyle:on parameter.number
      val tokenized = (f +: others).map(_.tokenize(
        languageDetector = languageDetector,
        analyzer = analyzer,
        autoDetectLanguage = autoDetectLanguage,
        autoDetectThreshold = autoDetectThreshold,
        defaultLanguage = defaultLanguage,
        minTokenLength = minTokenLength,
        toLowercase = toLowercase
      ))
      val hashedFeatures = new OPCollectionHashingVectorizer[TextList]()
        .setInput(tokenized)
        .setNumFeatures(numHashes)
        .setHashWithIndex(hashWithIndex)
        .setPrependFeatureName(prependFeatureName)
        .setHashSpaceStrategy(hashSpaceStrategy)
        .setHashAlgorithm(hashAlgorithm)
        .setBinaryFreq(binaryFreq)
        .getOutput()

      if (trackNulls) {
        val nullIndicators = new TextListNullTransformer[TextList]().setInput(tokenized).getOutput()
        new VectorsCombiner().setInput(hashedFeatures, nullIndicators).getOutput()
      }
      else hashedFeatures
    }

    /**
     * Vectorize text features by treating low cardinality text features as categoricals and
     * applying hashing trick to high caridinality ones.
     *
     * @param maxCategoricalCardinality max cardinality for a text feature to be treated as categorical
     * @param numHashes                 number of features (hashes) to generate
     * @param autoDetectLanguage        indicates whether to attempt language detection
     * @param minTokenLength            minimum token length, >= 1.
     * @param toLowercase               indicates whether to convert all characters to lowercase before analyzing
     * @param cleanText                 indicates whether to ignore capitalization and punctuation
     * @param trackNulls                indicates whether or not to track null values in a separate column.
     * @param topK                      number of most common elements to be used as categorical pivots
     * @param minSupport                minimum number of occurences an element must have to appear in pivot
     * @param unseenName                name to give indexes which do not have a label name associated with them
     * @param hashWithIndex             include indices when hashing a feature that has them (OPLists or OPVectors)
     * @param binaryFreq                if true, term frequency vector will be binary such that non-zero term
     *                                  counts will be set to 1.0
     * @param prependFeatureName        if true, prepends a input feature name to each token of that feature
     * @param autoDetectThreshold       Language detection threshold. If none of the detected languages have
     *                                  confidence greater than the threshold then defaultLanguage is used.
     * @param hashSpaceStrategy         strategy to determine whether to use shared hash space for all included features
     * @param defaultLanguage           default language to assume in case autoDetectLanguage is disabled or
     *                                  failed to make a good enough prediction.
     * @param hashAlgorithm             hash algorithm to use
     * @param others                    additional text features
     * @return result feature of type Vector
     */
    // scalastyle:off parameter.number
    def smartVectorize
    (
      maxCategoricalCardinality: Int,
      numHashes: Int,
      autoDetectLanguage: Boolean,
      minTokenLength: Int,
      toLowercase: Boolean,
      cleanText: Boolean = TransmogrifierDefaults.CleanText,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls,
      topK: Int = TransmogrifierDefaults.TopK,
      minSupport: Int = TransmogrifierDefaults.MinSupport,
      unseenName: String = TransmogrifierDefaults.OtherString,
      hashWithIndex: Boolean = TransmogrifierDefaults.HashWithIndex,
      binaryFreq: Boolean = TransmogrifierDefaults.BinaryFreq,
      prependFeatureName: Boolean = TransmogrifierDefaults.PrependFeatureName,
      autoDetectThreshold: Double = TextTokenizer.AutoDetectThreshold,
      hashSpaceStrategy: HashSpaceStrategy = TransmogrifierDefaults.HashSpaceStrategy,
      defaultLanguage: Language = TextTokenizer.DefaultLanguage,
      hashAlgorithm: HashAlgorithm = TransmogrifierDefaults.HashAlgorithm,
      others: Array[FeatureLike[T]] = Array.empty
    ): FeatureLike[OPVector] = {
      // scalastyle:on parameter.number
      new SmartTextVectorizer[T]()
        .setInput(f +: others)
        .setMaxCardinality(maxCategoricalCardinality)
        .setCleanText(cleanText)
        .setTrackNulls(trackNulls)
        .setAutoDetectLanguage(autoDetectLanguage)
        .setAutoDetectThreshold(autoDetectThreshold)
        .setDefaultLanguage(defaultLanguage)
        .setMinTokenLength(minTokenLength)
        .setToLowercase(toLowercase)
        .setTopK(topK)
        .setMinSupport(minSupport)
        .setUnseenName(unseenName)
        .setNumFeatures(numHashes)
        .setHashWithIndex(hashWithIndex)
        .setPrependFeatureName(prependFeatureName)
        .setHashSpaceStrategy(hashSpaceStrategy)
        .setHashAlgorithm(hashAlgorithm)
        .setBinaryFreq(binaryFreq)
        .getOutput()
    }

    /**
     * Apply [[OpStringIndexerNoFilter]] estimator.
     *
     * A label indexer that maps a text column of labels to an ML feature of label indices.
     * The indices are in [0, numLabels), ordered by label frequencies.
     * So the most frequent label gets index 0.
     *
     * @param unseenName    name to give strings that appear in transform but not in fit
     * @param handleInvalid how to transform values not seen in fitting
     * @see [[OpIndexToString]] for the inverse transformation
     *
     * @return indexed real feature
     */
    def indexed(
      unseenName: String = OpStringIndexerNoFilter.UnseenNameDefault,
      handleInvalid: StringIndexerHandleInvalid = StringIndexerHandleInvalid.NoFilter
    ): FeatureLike[RealNN] = {
      handleInvalid match {
        case StringIndexerHandleInvalid.NoFilter => f.transformWith(
          new OpStringIndexerNoFilter[T]().setUnseenName(unseenName)
        )
        case _ => f.transformWith(new OpStringIndexer[T]().setHandleInvalid(handleInvalid))
      }
    }

    /**
     * Tokenize text using the provided analyzer
     *
     * @param languageDetector    a language detector instance
     * @param analyzer            a text analyzer instance
     * @param autoDetectLanguage  indicates whether to attempt language detection
     * @param autoDetectThreshold Language detection threshold. If none of the detected languages have
     *                            confidence greater than the threshold then defaultLanguage is used.
     * @param defaultLanguage     default language to assume in case autoDetectLanguage is disabled or
     *                            failed to make a good enough prediction.
     * @param minTokenLength      minimum token length, >= 1.
     * @param toLowercase         indicates whether to convert all characters to lowercase before analyzing
     * @return tokenized feature
     */
    def tokenize(
      languageDetector: LanguageDetector,
      analyzer: TextAnalyzer,
      autoDetectLanguage: Boolean,
      autoDetectThreshold: Double,
      defaultLanguage: Language,
      minTokenLength: Int,
      toLowercase: Boolean
    ): FeatureLike[TextList] =
      f.transformWith(
        new TextTokenizer[T](analyzer = analyzer, languageDetector = languageDetector)
          .setAutoDetectLanguage(autoDetectLanguage)
          .setDefaultLanguage(defaultLanguage)
          .setAutoDetectThreshold(autoDetectThreshold)
          .setMinTokenLength(minTokenLength)
          .setToLowercase(toLowercase)
      )

    /**
     * Tokenize text using [[LuceneTextAnalyzer]] with [[OptimaizeLanguageDetector]]
     *
     * @param autoDetectLanguage  indicates whether to attempt language detection
     * @param autoDetectThreshold Language detection threshold. If none of the detected languages have
     *                            confidence greater than the threshold then defaultLanguage is used.
     * @param defaultLanguage     default language to assume in case autoDetectLanguage is disabled or
     *                            failed to make a good enough prediction.
     * @param minTokenLength      minimum token length, >= 1.
     * @param toLowercase         indicates whether to convert all characters to lowercase before analyzing
     * @param stripHtml           indicates whether to strip HTML tags from the text or not before analyzing
     * @return tokenized feature
     */
    def tokenize(
      autoDetectLanguage: Boolean = TextTokenizer.AutoDetectLanguage,
      autoDetectThreshold: Double = TextTokenizer.AutoDetectThreshold,
      defaultLanguage: Language = TextTokenizer.DefaultLanguage,
      minTokenLength: Int = TextTokenizer.MinTokenLength,
      toLowercase: Boolean = TextTokenizer.ToLowercase,
      stripHtml: Boolean = TextTokenizer.StripHtml
    ): FeatureLike[TextList] =
      tokenize(
        languageDetector = TextTokenizer.LanguageDetector,
        analyzer = if (stripHtml) TextTokenizer.AnalyzerHtmlStrip else TextTokenizer.Analyzer,
        autoDetectLanguage = autoDetectLanguage,
        autoDetectThreshold = autoDetectThreshold,
        defaultLanguage = defaultLanguage,
        minTokenLength = minTokenLength,
        toLowercase = toLowercase
      )

    /**
     * Tokenize text using regex pattern matching to construct distinct tokens.
     * NOTE: This Tokenizer does not output tokens that are of zero length.
     *
     * @param pattern        is the regular expression
     * @param group          selects the matching group as the token (default: -1, which is equivalent to "split".
     *                       In this case, the tokens will be equivalent to the output from (without empty tokens).
     * @param minTokenLength minimum token length, >= 1.
     * @param toLowercase    indicates whether to convert all characters to lowercase before analyzing
     * @return tokenized feature
     */
    def tokenizeRegex
    (
      pattern: String,
      group: Int = -1,
      minTokenLength: Int = TextTokenizer.MinTokenLength,
      toLowercase: Boolean = TextTokenizer.ToLowercase
    ): FeatureLike[TextList] = {
      require(Try(Pattern.compile(pattern)).isSuccess, s"Invalid regex pattern: $pattern")

      // A simple Lucene analyzer with regex Pattern Tokenizer
      val analyzer = new LuceneTextAnalyzer(analyzers = lang => new Analyzer {
        def createComponents(fieldName: String): TokenStreamComponents = {
          val regex = Pattern.compile(pattern)
          val source = new PatternTokenizer(regex, group)
          new TokenStreamComponents(source)
        }
      })
      tokenize(
        languageDetector = TextTokenizer.LanguageDetector,
        analyzer = analyzer,
        autoDetectLanguage = false,
        autoDetectThreshold = 1.0,
        defaultLanguage = Language.Unknown,
        minTokenLength = minTokenLength,
        toLowercase = toLowercase
      )
    }

    /**
     * Detect the language of the text
     *
     * @param languageDetector a language detector instance
     * @return real map feature containing the detected languages with confidence scores.
     *         Confidence score is range of [0.0, 1.0], with higher values implying greater confidence.
     */
    def detectLanguages(languageDetector: LanguageDetector = LangDetector.DefaultDetector): FeatureLike[RealMap] =
      f.transformWith(new LangDetector[T](languageDetector))

    /**
     * Find name entities of the text using OpenNLP [[OpenNLPAnalyzer]]
     *
     * @param languageDetector    a language detector instance
     * @param analyzer            a text analyzer instance
     * @param sentenceSplitter    sentence splitter
     * @param tagger              name entity recognition tagger
     * @param autoDetectLanguage  indicates whether to attempt language detection
     * @param autoDetectThreshold Language detection threshold. If none of the detected languages have
     *                            confidence greater than the threshold then defaultLanguage is used.
     * @param defaultLanguage     default language to assume in case autoDetectLanguage is disabled or
     *                            failed to make a good enough prediction.
     * @return name entity sets feature
     */
    def recognizeEntities
    (
      languageDetector: LanguageDetector = NameEntityRecognizer.LanguageDetector,
      analyzer: TextAnalyzer = NameEntityRecognizer.Analyzer,
      sentenceSplitter: SentenceSplitter = NameEntityRecognizer.Splitter,
      tagger: NameEntityTagger[_ <: TaggerResult] = NameEntityRecognizer.Tagger,
      autoDetectLanguage: Boolean = NameEntityRecognizer.AutoDetectLanguage,
      autoDetectThreshold: Double = NameEntityRecognizer.AutoDetectThreshold,
      defaultLanguage: Language = NameEntityRecognizer.DefaultLanguage
    ): FeatureLike[MultiPickListMap] = {
      f.transformWith(
        new NameEntityRecognizer[T](languageDetector, analyzer, sentenceSplitter, tagger)
          .setAutoDetectLanguage(autoDetectLanguage)
          .setAutoDetectThreshold(autoDetectThreshold)
          .setDefaultLanguage(defaultLanguage)
      )
    }
  }

  implicit class RichPhoneFeature(val f: FeatureLike[Phone]) {

    /**
     * Filter phone numbers given their country and returns only valid
     * entries. Invalid entries are left blank.
     *
     * @param regionCode    feature containing country information
     * @param countryCodes  map of possible countries and their codes
     * @param isStrict      strict comparison if true.
     * @param defaultRegion default locale if region code is not valid
     * @return result feature of type Phone
     */
    def parsePhone
    (
      regionCode: FeatureLike[Text],
      countryCodes: Map[String, String] = PhoneNumberParser.DefaultCountryCodes,
      isStrict: Boolean = PhoneNumberParser.StrictValidation,
      defaultRegion: String = PhoneNumberParser.DefaultRegion
    ): FeatureLike[Phone] = {
      f.transformWith(
        new ParsePhoneNumber()
          .setCodesAndCountries(countryCodes)
          .setStrictness(isStrict)
          .setDefaultRegion(defaultRegion),
        regionCode
      )
    }


    /**
     * Filter phone numbers given their country and returns only valid
     * entries. Invalid entries are left blank.
     *
     * @param isStrict      strict comparison if true.
     * @param defaultRegion default locale if region code is not valid
     * @return result feature of type Phone
     */
    def parsePhoneDefaultCountry
    (
      isStrict: Boolean = PhoneNumberParser.StrictValidation,
      defaultRegion: String = PhoneNumberParser.DefaultRegion
    ): FeatureLike[Phone] = {
      f.transformWith(
        new ParsePhoneDefaultCountry()
          .setStrictness(isStrict)
          .setDefaultRegion(defaultRegion)
      )
    }

    /**
     * Returns new feature where 1 represents valid numbers and 0 represents invalid numbers checked against
     * the location associated with the number
     *
     * @param regionCode    feature containing country information
     * @param countryCodes  map of possible countries and their codes
     * @param isStrict      strict comparison if true.
     * @param defaultRegion default locale if region code is not valid
     * @return result feature of type Binary
     */
    def isValidPhone
    (
      regionCode: FeatureLike[Text],
      countryCodes: Map[String, String] = PhoneNumberParser.DefaultCountryCodes,
      isStrict: Boolean = PhoneNumberParser.StrictValidation,
      defaultRegion: String = PhoneNumberParser.DefaultRegion
    ): FeatureLike[Binary] = {
      f.transformWith(
        new IsValidPhoneNumber()
          .setCodesAndCountries(countryCodes)
          .setStrictness(isStrict)
          .setDefaultRegion(defaultRegion),
        regionCode
      )
    }

    /**
     * Returns new feature where true represents valid numbers and false represents invalid numbers
     *
     * @param isStrict      strict comparison if true.
     * @param defaultRegion default locale if region code is not valid
     * @return result feature of type Binary
     */
    def isValidPhoneDefaultCountry
    (
      isStrict: Boolean = PhoneNumberParser.StrictValidation,
      defaultRegion: String = PhoneNumberParser.DefaultRegion
    ): FeatureLike[Binary] = {
      f.transformWith(
        new IsValidPhoneDefaultCountry()
          .setStrictness(isStrict)
          .setDefaultRegion(defaultRegion)
      )
    }

    /**
     * Returns a vector for phone numbers where the first element is 1 if the number is valid for the given region
     * 0 if invalid and with an optional second element idicating if the phone number was null
     *
     * @param defaultRegion region against which to check phone validity
     * @param isStrict      strict validation means cannot have extra digits
     * @param trackNulls    produce column indicating if the number was null
     * @param fillValue     value to fill in for nulls in vactor creation
     * @param others        other phone numbers to vectorize
     * @return vector feature containing information about phone number
     */
    def vectorize(
      defaultRegion: String,
      isStrict: Boolean = PhoneNumberParser.StrictValidation,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls,
      fillValue: Boolean = TransmogrifierDefaults.BinaryFillValue,
      others: Array[FeatureLike[Phone]] = Array.empty
    ): FeatureLike[OPVector] = {
      val valid = (f +: others).map(_.isValidPhoneDefaultCountry(defaultRegion = defaultRegion, isStrict = isStrict))
      valid.head.vectorize(others = valid.tail, fillValue = fillValue, trackNulls = trackNulls)
    }

  }

  implicit class RichEmailFeature(val f: FeatureLike[Email]) {

    /**
     * Extract email prefixes
     *
     * @return email prefix
     */
    def toEmailPrefix: FeatureLike[Text] = f.map[Text](_.prefix.toText, "prefix")

    /**
     * Extract email domains
     *
     * @return email domain
     */
    def toEmailDomain: FeatureLike[Text] = f.map[Text](_.domain.toText, "domain")

    /**
     * Converts a sequence of [[Email]] features into a vector, extracting the domains of the e-mails
     * and keeping the top K occurrences of each feature, along with an extra column per feature
     * indicating how many values were not in the top K.
     *
     * @param others     Other [[Email]] features
     * @param topK       How many values to keep in the vector
     * @param minSupport Min times a value must occur to be retained in pivot
     * @param cleanText  If true, ignores capitalization and punctuations when grouping categories
     * @param trackNulls keep an extra column that indicated if feature was null
     * @return The vectorized features
     */
    def vectorize
    (
      topK: Int,
      cleanText: Boolean,
      minSupport: Int,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls,
      others: Array[FeatureLike[Email]] = Array.empty
    ): FeatureLike[OPVector] = {
      val domains = (f +: others).map(_.map[PickList](_.domain.toPickList))
      domains.head.pivot(others = domains.tail, topK = topK, minSupport = minSupport, cleanText = cleanText,
        trackNulls = trackNulls
      )
    }

  }

  implicit class RichURLFeature(val f: FeatureLike[URL]) {

    /**
     * Extract url domain, i.e. salesforce.com, data.com etc.
     */
    def toDomain: FeatureLike[Text] = f.map[Text](_.domain.toText, "urlDomain")

    /**
     * Extracts url protocol, i.e. http, https, ftp etc.
     */
    def toProtocol: FeatureLike[Text] = f.map[Text](_.protocol.toText, "urlProtocol")

    /**
     * Verifies if the url is of correct form of "Uniform Resource Identifiers (URI): Generic Syntax"
     * RFC2396 (http://www.ietf.org/rfc/rfc2396.txt)
     * Default valid protocols are: http, https, ftp.
     */
    def isValidUrl: FeatureLike[Binary] = f.exists(_.isValid)

    /**
     * Verifies if the url is of correct form of "Uniform Resource Identifiers (URI): Generic Syntax"
     * RFC2396 (http://www.ietf.org/rfc/rfc2396.txt)
     *
     * @param protocols url protocols to consider valid, i.e. http, https, ftp etc.
     */
    def isValidUrl(protocols: Array[String]): FeatureLike[Binary] = f.exists(_.isValid(protocols))

    /**
     * Converts a sequence of [[URL]] features into a vector, extracting the domains of the valid urls
     * and keeping the top K occurrences of each feature, along with an extra column per feature
     * indicating how many values were not in the top K.
     *
     * @param others     Other [[URL]] features
     * @param topK       How many values to keep in the vector
     * @param minSupport Min times a value must occur to be retained in pivot
     * @param cleanText  If true, ignores capitalization and punctuations when grouping categories
     * @param trackNulls keep an extra column that indicated if feature was null
     * @return The vectorized features
     */
    def vectorize
    (
      topK: Int,
      cleanText: Boolean,
      minSupport: Int,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls,
      others: Array[FeatureLike[URL]] = Array.empty
    ): FeatureLike[OPVector] = {
      val domains = (f +: others).map(_.map[PickList](v => if (v.isValid) v.domain.toPickList else PickList.empty))
      domains.head.pivot(others = domains.tail, topK = topK, minSupport = minSupport, cleanText = cleanText,
        trackNulls = trackNulls
      )
    }

  }

  implicit class RichBase64Feature(val f: FeatureLike[Base64]) {

    /**
     * Detect MIME type for Base64 encoded binary data
     *
     * @param typeHint MIME type hint, i.e. 'application/json', 'text/plain' etc.
     * @return mime type as text
     */
    def detectMimeTypes(typeHint: Option[String] = None): FeatureLike[Text] = {
      val detector = new MimeTypeDetector()
      typeHint.foreach(detector.setTypeHint)
      f.transformWith(detector)
    }

    /**
     * Extracts Base64 features (MIME type etc.),
     * then converts those into PickList features and vectorizes them.
     *
     * @param topK       number of values to keep for each key
     * @param minSupport min times a value must occur to be retained in pivot
     * @param cleanText  clean text before pivoting
     * @param trackNulls keep an extra column that indicated if feature was null
     * @param typeHint   MIME type hint, i.e. 'application/json', 'text/plain' etc.
     * @param others     other features of the same type
     * @return result feature of type vector
     */
    def vectorize(
      topK: Int,
      minSupport: Int,
      cleanText: Boolean,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls,
      typeHint: Option[String] = None,
      others: Array[FeatureLike[Base64]] = Array.empty
    ): FeatureLike[OPVector] = {

      val feats: Array[FeatureLike[PickList]] =
        (f +: others).map(_.detectMimeTypes(typeHint).map[PickList](_.value.toPickList))

      feats.head.vectorize(
        topK = topK, minSupport = minSupport, cleanText = cleanText, trackNulls = trackNulls, others = feats.tail
      )
    }

  }

  implicit class RichPickListFeature(val f: FeatureLike[PickList]) {

    /**
     * Converts a sequence of [[PickList]] features into a vector keeping the top K occurrences of each feature,
     * along with an extra column per feature indicating how many values were not in the top K.
     *
     * @param others     Other [[PickList]] features to include in pivot
     * @param topK       How many values to keep in the vector
     * @param minSupport Min times a value must occur to be retained in pivot
     * @param cleanText  If true, ignores capitalization and punctuations when grouping categories
     * @param trackNulls keep an extra column that indicated if feature was null
     * @return The vectorized features
     */
    def vectorize
    (
      topK: Int,
      minSupport: Int,
      cleanText: Boolean,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls,
      others: Array[FeatureLike[PickList]] = Array.empty
    ): FeatureLike[OPVector] = {
      f.pivot(others = others, topK = topK, minSupport = minSupport, cleanText = cleanText, trackNulls = trackNulls)
    }

  }

  implicit class RichComboBoxFeature(val f: FeatureLike[ComboBox]) {

    /**
     * Converts a sequence of [[ComboBox]] features into a vector keeping the top K occurrences of each feature,
     * along with an extra column per feature indicating how many values were not in the top K.
     *
     * @param others     Other [[ComboBox]] features to include in pivot
     * @param topK       How many values to keep in the vector
     * @param minSupport Min times a value must occur to be retained in pivot
     * @param cleanText  If true, ignores capitalization and punctuations when grouping categories
     * @param trackNulls keep an extra column that indicated if feature was null
     * @return The vectorized features
     */
    def vectorize
    (
      topK: Int,
      minSupport: Int,
      cleanText: Boolean,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls,
      others: Array[FeatureLike[ComboBox]] = Array.empty
    ): FeatureLike[OPVector] = {
      f.pivot(others = others, topK = topK, minSupport = minSupport, cleanText = cleanText, trackNulls = trackNulls)
    }

  }

  implicit class RichIdFeature(val f: FeatureLike[ID]) {

    /**
     * Converts a sequence of [[ID]] features into a vector keeping the top K occurrences of each feature,
     * along with an extra column per feature indicating how many values were not in the top K.
     *
     * @param others     Other [[ID]] features to include in pivot
     * @param topK       How many values to keep in the vector
     * @param minSupport Min times a value must occur to be retained in pivot
     * @param cleanText  If true, ignores capitalization and punctuations when grouping categories
     * @param trackNulls keep an extra column that indicated if feature was null
     * @return The vectorized features
     */
    def vectorize
    (
      topK: Int,
      minSupport: Int,
      cleanText: Boolean,
      trackNulls: Boolean = TransmogrifierDefaults.TrackNulls,
      others: Array[FeatureLike[ID]] = Array.empty
    ): FeatureLike[OPVector] = {
      f.pivot(others = others, topK = topK, minSupport = minSupport, cleanText = cleanText, trackNulls = trackNulls)
    }

  }

}
