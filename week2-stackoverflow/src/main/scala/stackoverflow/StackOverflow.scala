package stackoverflow

import org.apache.spark.SparkConf
import org.apache.spark.SparkContext
import org.apache.spark.SparkContext._
import org.apache.spark.rdd.RDD
import annotation.tailrec
import scala.reflect.ClassTag

/** A raw stackoverflow posting, either a question or an answer */
case class Posting(postingType: Int, id: Int, acceptedAnswer: Option[Int], parentId: Option[Int], score: Int, tags: Option[String]) extends Serializable


/** The main class */
object StackOverflow extends StackOverflow {

  @transient lazy val conf: SparkConf = new SparkConf().setMaster("local").setAppName("StackOverflow")
  @transient lazy val sc: SparkContext = new SparkContext(conf)

  /** Main function */
  def main(args: Array[String]): Unit = {

    val lines   = sc.textFile("src/main/resources/stackoverflow/stackoverflow.csv")
//    lines.take(1).foreach(println)
    val raw     = rawPostings(lines)
//    raw.take(1).foreach(println)
    val grouped = groupedPostings(raw)
 //   grouped.take(1).foreach(println)
    val scored  = scoredPostings(grouped)/*.sample(true,0.003,0)*/
//    scored.take(1).foreach(println)
    val vectors = vectorPostings(scored)
//    vectors.take(1).foreach(println)
    println(vectors.count)
    
//    assert(vectors.count() == 2121822, "Incorrect number of vectors: " + vectors.count())

    val means   = kmeans(sampleVectors(vectors), vectors, debug = true)
//    means.take(1).foreach(println)
    val results = clusterResults(means, vectors)
//    results.take(1).foreach(println)
    printResults(results)
  }
}


/** The parsing and kmeans methods */
class StackOverflow extends Serializable {

  /** Languages */
  val langs =
    List(
      "JavaScript", "Java", "PHP", "Python", "C#", "C++", "Ruby", "CSS",
      "Objective-C", "Perl", "Scala", "Haskell", "MATLAB", "Clojure", "Groovy")

  /** K-means parameter: How "far apart" languages should be for the kmeans algorithm? */
  def langSpread = 50000
  assert(langSpread > 0, "If langSpread is zero we can't recover the language from the input data!")

  /** K-means parameter: Number of clusters */
  def kmeansKernels = 45

  /** K-means parameter: Convergence criteria */
  def kmeansEta: Double = 20.0D

  /** K-means parameter: Maximum iterations */
  def kmeansMaxIterations = 120


  //
  //
  // Parsing utilities:
  //
  //

  /** Load postings from the given file */
  def rawPostings(lines: RDD[String]): RDD[Posting] =
    lines.map(line => {
      val arr = line.split(",")
      Posting(postingType =    arr(0).toInt,
              id =             arr(1).toInt,
              acceptedAnswer = if (arr(2) == "") None else Some(arr(2).toInt),
              parentId =       if (arr(3) == "") None else Some(arr(3).toInt),
              score =          arr(4).toInt,
              tags =           if (arr.length >= 6) Some(arr(5).intern()) else None)
    })


  /** Group the questions and answers together */
  def groupedPostings(postings: RDD[Posting]): RDD[(Int, Iterable[(Posting, Posting)])] = {
    //cache to avoid recomputation
    //TODO should cache be done outside of this function?
    val questions=postings.filter(_.postingType==1)
                          .map(x=>(x.id,x))
    val answers=postings.filter(_.postingType==2)
                        .map(x=>(x.parentId.get,x)) //just get. If it`s None crush!
    //Inner join excludes questions without an answer.
    //leftOuterJoin needs a change at return Type RDD[(Int, Iterable[(Posting, Option[Posting])])]
    val pairs=questions.join(answers)
    pairs.groupByKey()

  }


  /** Compute the maximum score for each posting */
  def scoredPostings(grouped: RDD[(Int, Iterable[(Posting, Posting)])]): RDD[(Posting, Int)] = {

    def answerHighScore(as: Array[Posting]): Int = {
      var highScore = 0
          var i = 0
          while (i < as.length) {
            val score = as(i).score
                if (score > highScore)
                  highScore = score                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                                       
                  i += 1
          }
      highScore
    }

    grouped.map{
      case (_,ls)=>(ls.find(_=>true).get._1,answerHighScore(ls.unzip._2.toArray))
    }
  }


  /** Compute the vectors for the kmeans */
  def vectorPostings(scored: RDD[(Posting, Int)]): RDD[(Int, Int)] = {
    /** Return optional index of first language that occurs in `tags`. */
    def firstLangInTag(tag: Option[String], ls: List[String]): Option[Int] = {
      if (tag.isEmpty) None
      else if (ls.isEmpty) None
      else if (tag.get == ls.head) Some(0) // index: 0
      else {
        val tmp = firstLangInTag(tag, ls.tail)
        tmp match {
          case None => None
          case Some(i) => Some(i + 1) // index i in ls.tail => index i+1
        }
      }
    }

    val vectors=scored
    .map{case (post,x)=>{
       val n=firstLangInTag(post.tags,langs)
       (n,x)}
    }
    .filter{case(opt,_)=>(!opt.isEmpty)}
    .map   {case (opt,x)=>(langSpread*opt.get,x)}
    
    //In Discussion Forum "My summary to get 10/10 got this assignment"
    //it was mentioned that vectors should not be cached in main but in functions
    //as grader doesn`t run main
    vectors.cache()
  }


  /** Sample the vectors */
  def sampleVectors(vectors: RDD[(Int, Int)]): Array[(Int, Int)] = {

    assert(kmeansKernels % langs.length == 0, "kmeansKernels should be a multiple of the number of languages studied.")
    val perLang = kmeansKernels / langs.length

    // http://en.wikipedia.org/wiki/Reservoir_sampling
    def reservoirSampling(lang: Int, iter: Iterator[Int], size: Int): Array[Int] = {
      val res = new Array[Int](size)
      val rnd = new util.Random(lang)

      for (i <- 0 until size) {
        assert(iter.hasNext, s"iterator must have at least $size elements")
        res(i) = iter.next
      }

      var i = size.toLong
      while (iter.hasNext) {
        val elt = iter.next
        val j = math.abs(rnd.nextLong) % i
        if (j < size)
          res(j.toInt) = elt
        i += 1
      }

      res
    }

    val res =
      if (langSpread < 500)
        // sample the space regardless of the language
        vectors.takeSample(false, kmeansKernels, 42)
      else
        // sample the space uniformly from each language partition
        vectors.groupByKey.flatMap({
          case (lang, vectors) => reservoirSampling(lang, vectors.toIterator, perLang).map((lang, _))
        }).collect()

    assert(res.length == kmeansKernels, res.length)
    res
  }


  //
  //
  //  Kmeans method:
  //
  //

  /** Main kmeans computation */
  @tailrec final def kmeans(means: Array[(Int, Int)], vectors: RDD[(Int, Int)], iter: Int = 1, debug: Boolean = false): Array[(Int, Int)] = {
    def addTriple(x:(Int,Int,Int),y:(Int,Int,Int)):(Int,Int,Int)=
    (x._1+y._1,x._2+y._2,x._3+y._3)
    
    val newMeans = means.clone() // you need to compute newMeans      
    
    //This update trick(last line) was found at discussion forum week 2 with title
    //"kmeans initializes with non-distinct means"
    //problem was that initial means were not all distinct,
    //so some of them were lost and we had an assert error
    vectors
//    .map(vector=>(findClosest(vector,means),(vector._1,vector._2,1)))
      .map(vector=>(findClosest(vector,means),vector))
      //our custom average with reduceByKey din`t work
//    .reduceByKey(addTriple)
//    .mapValues(tr=>(tr._1/tr._3,tr._2/tr._3))
      .groupByKey()
      .mapValues(averageVectors)
      .collect
      .foreach({ case (i,vl)=>newMeans.update(i,vl)})
                    
    val distance = euclideanDistance(means, newMeans)

    if (debug) {
      println(s"""Iteration: $iter
                 |  * current distance: $distance
                 |  * desired distance: $kmeansEta
                 |  * means:""".stripMargin)
      for (idx <- 0 until means.length)
      println(f"   ${means(idx).toString}%20s ==> ${newMeans(idx).toString}%20s  " +
              f"  distance: ${euclideanDistance(means(idx), newMeans(idx))}%8.0f")
    }

    if (converged(distance))
      newMeans
    else if (iter < kmeansMaxIterations)
      kmeans(newMeans, vectors, iter + 1, debug)
    else {
      println("Reached max iterations!")
      newMeans
    }
  }




  //
  //
  //  Kmeans utilities:
  //
  //

  /** Decide whether the kmeans clustering converged */
  def converged(distance: Double) =
    distance < kmeansEta


  /** Return the euclidean distance between two points */
  def euclideanDistance(v1: (Int, Int), v2: (Int, Int)): Double = {
    val part1 = (v1._1 - v2._1).toDouble * (v1._1 - v2._1)
    val part2 = (v1._2 - v2._2).toDouble * (v1._2 - v2._2)
    part1 + part2
  }

  /** Return the euclidean distance between two points */
  def euclideanDistance(a1: Array[(Int, Int)], a2: Array[(Int, Int)]): Double = {
    try {assert(a1.length == a2.length)}
    catch {
      case _:Throwable=> 
        {println ("EXCEPTION LOG")
         println("a1")
         a1.foreach(println)
         println("a2")
         a2.foreach(println)
         println(a1.length+"=/="+ a2.length)
         assert(false)
        }
    }
    var sum = 0d
    var idx = 0
    while(idx < a1.length) {
      sum += euclideanDistance(a1(idx), a2(idx))
      idx += 1
    }
    sum
  }

  /** Return the closest point */
  def findClosest(p: (Int, Int), centers: Array[(Int, Int)]): Int = {
    var bestIndex = 0
    var closest = Double.PositiveInfinity
    for (i <- 0 until centers.length) {
      val tempDist = euclideanDistance(p, centers(i))
      if (tempDist < closest) {
        closest = tempDist
        bestIndex = i
      }
    }
    bestIndex
  }


  /** Average the vectors */
  def averageVectors(ps: Iterable[(Int, Int)]): (Int, Int) = {
    val iter = ps.iterator
    var count = 0
    var comp1: Long = 0
    var comp2: Long = 0
    while (iter.hasNext) {
      val item = iter.next
      comp1 += item._1
      comp2 += item._2
      count += 1
    }
    ((comp1 / count).toInt, (comp2 / count).toInt)
  }




  //
  //
  //  Displaying results:
  //
  //
  def clusterResults(means: Array[(Int, Int)], vectors: RDD[(Int, Int)]): Array[(String, Double, Int, Int)] = {
    val closest = vectors.map(p => (findClosest(p, means), p))
    val closestGrouped = closest.groupByKey():RDD[(Int,Iterable[(Int,Int)])]

    val median = closestGrouped.mapValues { vs =>
      val ls=vs.toList
      val mp=ls.groupBy(_._1).mapValues(ls=>(ls.length,ls.map(x=>x._2))):Map[Int,(Int,List[Int])]
      // most common language in the cluster
      val (idMax,max)=mp.foldLeft (-1,-1) {
        case (acc,(k,v))=>if(v._1>acc._1) (k,v._1) else acc}
      
      val langLabel: String =   langs(idMax/langSpread)
      val maxNum = vs.filter(_._1==idMax).count(_=>true) //foldLeft(0)((acc,x)=>x._2+acc)
      val clusterSize: Int    = vs.size
//    val allNum = vs.foldLeft(0)((acc,x)=>x._2+acc)
      val langPercent: Double =100.0*maxNum/clusterSize
         // percent of the questions in the most common language
      val forMed=vs.toArray.sortBy(_._2)
      val medianScore: Int =
        if (clusterSize%2==0) (forMed(clusterSize/2-1)._2+forMed(clusterSize/2)._2)/2
        else forMed(clusterSize/2)._2

      (langLabel, langPercent, clusterSize, medianScore)
    }

    median.collect().map(_._2).sortBy(_._4)
  }

  def printResults(results: Array[(String, Double, Int, Int)]): Unit = {
    println("Resulting clusters:")
    println("  Score  Dominant language (%percent)  Questions")
    println("================================================")
    for ((lang, percent, size, score) <- results)
      println(f"${score}%7d  ${lang}%-17s (${percent}%-5.1f%%)      ${size}%7d")
  }
}

