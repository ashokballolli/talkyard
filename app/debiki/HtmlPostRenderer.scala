/**
 * Copyright (c) 2012 Kaj Magnus Lindberg (born 1979)
 */

package debiki

import com.debiki.v0._
//import com.twitter.ostrich.stats.Stats
import java.{util => ju, io => jio}
import scala.collection.JavaConversions._
import _root_.scala.xml.{NodeSeq, Node, Elem, Text, XML, Attribute}
import FlagReason.FlagReason
import Prelude._
import DebikiHttp._
import HtmlUtils._



case class RenderedComment(
  html: Node,
  replyBtnText: NodeSeq,
  topRatingsText: Option[String],
  templCmdNodes: NodeSeq)



case class HtmlPostRenderer(
  page: Debate,
  pageStats: PageStats,
  hostAndPort: String) {

  // rename later... to what?
  def debate = page


  def renderPost(postId: String): RenderedComment = {
    val post = page.vipo(postId) getOrElse
       assErr("DwE209X5", "post id "+ postId +" on page "+ page.id)

    if (post.isTreeDeleted) _showDeletedTree(post)
    else if (post.isDeleted) _showDeletedComment(post)
    else if (post.id == Page.TitleId) _renderPageTitle(post)
    else _showComment(post)
  }


  private def _showDeletedTree(vipo: ViPo): RenderedComment = {
    _showDeletedComment(vipo, wholeTree = true)
  }


  private def _showDeletedComment(vipo: ViPo, wholeTree: Boolean = false
                                     ): RenderedComment = {
    val cssPostId = "post-"+ vipo.id
    val deletion = vipo.firstDelete.get
    val deleter = debate.people.authorOf_!(deletion)
    // COULD add itemscope and itemtype attrs, http://schema.org/Comment
    val html =
    <div id={cssPostId} class='dw-p dw-p-dl'>
      <div class='dw-p-hd'>{
        if (wholeTree) "Thread" else "1 comment"
        } deleted by { _linkTo(deleter)
        /* COULD show flagsTop, e.g. "flagged spam".
        COULD include details, shown on click:
        Posted on ...,, rated ... deleted on ..., reasons for deletion: ...
        X flags: ... -- but perhaps better / easier with a View link,
        that opens the deleted post, incl. details, in a new browser tab?  */}
      </div>
    </div>
    RenderedComment(html, replyBtnText = Nil, topRatingsText = None,
       templCmdNodes = Nil)
  }


  private def _showComment(vipo: ViPo): RenderedComment = {
    def post = vipo.post
    val editsApplied: List[ViEd] = vipo.editsAppliedDescTime
    val lastEditApplied = editsApplied.headOption
    val cssPostId = "post-"+ post.id
    val (cssArtclPost, cssArtclBody) =
      if (post.id != Page.BodyId) ("", "")
      else (" dw-ar-p", " dw-ar-p-bd")
    val isBodyOrArtclQstn = vipo.id == Page.BodyId ||
        vipo.meta.isArticleQuestion

    val (xmlTextInclTemplCmds, numLines) =
      HtmlPageSerializer._markupTextOf(vipo, hostAndPort)

    // Find any customized reply button text.
    var replyBtnText: NodeSeq = xml.Text("Reply")
    if (isBodyOrArtclQstn) {
      HtmlPageSerializer.findChildrenOfNode(
         withClass = "debiki-0-reply-button-text",
         in = xmlTextInclTemplCmds) foreach { replyBtnText = _ }
    }

    // Find any template comands.
    val (templCmdNodes: NodeSeq, xmlText: NodeSeq) =
      (Nil: NodeSeq, xmlTextInclTemplCmds)  // for now, ignore all
      //if (!isRootOrArtclQstn) (Nil, xmlTextInclTemplCmds)
      //else partitionChildsWithDataAttrs(in = xmlTextInclTemplCmds)

    val long = numLines > 9
    val cutS = if (long) " dw-x-s" else ""
    val author = debate.people.authorOf_!(post)

    val (flagsTop: NodeSeq, flagsDetails: NodeSeq) = {
      if (vipo.flags isEmpty) (Nil: NodeSeq, Nil: NodeSeq)
      else {
        import HtmlForms.FlagForm.prettify
        val mtime = toIso8601T(vipo.lastFlag.get.ctime)
        val fbr = vipo.flagsByReasonSorted
        (<span class='dw-p-flgs-top'>, flagged <em>{
            prettify(fbr.head._1).toLowerCase}</em></span>,
        <div class='dw-p-flgs-all' data-mtime={mtime}>{
          vipo.flags.length} flags: <ol class='dw-flgs'>{
            for ((r: FlagReason, fs: List[Flag]) <- fbr) yield
              <li class="dw-flg">{
                // The `×' is the multiplication sign, "\u00D7".
                prettify(r).toLowerCase +" × "+ fs.length.toString
              } </li>
          }</ol>
        </div>)
      }
    }

    val postRatingStats = pageStats.ratingStatsFor(post.id)
    // Sort the rating tags by their observed fittingness, descending
    // (the most popular tags first).
    val tagStatsSorted = postRatingStats.tagStats.toList.sortBy(
        -_._2.fitness.observedMean)
    val topTags = if (tagStatsSorted isEmpty) Nil else {
      // If there're any really popular tags ([the lower confidence limit on
      // the probability that they're used] is > 0.4),
      // show all those. Otherwise, show only the most popular tag(s).
      // (Oops, need not be `max' -- they're sorted by the *measured* prob,
      // not the lower conf limit -- well, hardly matters.)
      val maxLowerConfLimit = tagStatsSorted.head._2.fitness.lowerLimit
      val minLower = math.min(0.4, maxLowerConfLimit)
      tagStatsSorted.takeWhile(_._2.fitness.lowerLimit >= minLower)
    }

    val topTagsAsText: Option[String] = {
      def showRating(tagAndStats: Pair[String, TagStats]): String = {
        val tagName = tagAndStats._1
        val tagFitness = tagAndStats._2.fitness
        // A rating tag like "important!!" means "really important", many
        // people agree. And "important?" means "perhaps somewhat important",
        // some people agree.
        // COULD reduce font-size of ? to 85%, it's too conspicuous.
        val mark =
          if (tagFitness.lowerLimit > 0.9) "!!"
          else if (tagFitness.lowerLimit > 0.7) "!"
          else if (tagFitness.lowerLimit > 0.3) ""
          else "?"
        tagName + mark
        // COULD reduce font size of mark to 85%, or it clutters the ratings.
      }
      if (topTags isEmpty) None
      else Some(topTags.take(3).map(showRating(_)).mkString(", "))
    }

    val (ratingTagsTop: NodeSeq, ratingTagsDetails: NodeSeq) = {
      val rats = tagStatsSorted
      if (rats.isEmpty) (Nil: NodeSeq, Nil: NodeSeq)
      else {
        // List popular rating tags. Then all tags and their usage percents,
        // but those details are shown only if one clicks the post header.
        val topTagsAsHtml =
          if (topTagsAsText isEmpty) Nil
          else <span class='dw-p-r dw-p-r-top'>, rated <em>{
            topTagsAsText.get}</em></span>

        val tagDetails = <div class='dw-p-r-all'
             data-mtime={toIso8601T(postRatingStats.lastRatingDate)}>{
          postRatingStats.ratingCountUntrusty} ratings:
          <ol class='dw-p-r dw-rs'>{
          // Don't change whitespace, or `editInfo' perhaps won't
          // be able to append a ',' with no whitespace in front.
          for ((tagName: String, tagStats: TagStats) <- rats) yield
          <li class="dw-r" data-stats={
              ("lo: %.0f" format (100 * tagStats.fitness.lowerLimit)) +"%, "+
              "sum: "+ tagStats.countWeighted}> {
            tagName +" %.0f" format (
               100 * tagStats.fitness.observedMean)}% </li>
        }</ol></div>

        (topTagsAsHtml, tagDetails)
      }
    }

    val editInfo =
      // If closed: <span class='dw-p-re-cnt'>{count} replies</span>
      if (editsApplied.isEmpty) Nil
      else {
        val lastEditDate = vipo.modificationDati
        // ((This identity count doesn't take into account that a user
        // can have many identities, e.g. Twitter, Facebook and Gmail. So
        // even if many different *identities* have edited the post,
        // perhaps only one single actual *user* has edited it. Cannot easily
        // compare users though, because IdentitySimple maps to no user!))
        val editorsCount =
          editsApplied.map(edAp => debate.vied_!(edAp.id).identity_!.id).
          distinct.length
        lazy val editor =
          debate.people.authorOf_!(debate.editsById(lastEditApplied.get.id))
        <span class='dw-p-hd-e'>{
            Text(", edited ") ++
            (if (editorsCount > 1) {
              Text("by ") ++ <a>various people</a>
            } else if (editor.identity_!.id != author.identity_!.id) {
              Text("by ") ++ _linkTo(editor)
            } else {
              // Edited by the author. Don't repeat his/her name.
              Nil
            })
          }{dateAbbr(lastEditDate, "dw-p-at")}
        </span>
      }

    val postTitleXml: NodeSeq =
      // Currently only the page body can have a title.
      if (post.id != Page.BodyId) Nil
      else debate.titlePost map(_renderPageTitle(_).html) getOrElse Nil

    val commentHtml =
    <div id={cssPostId} class={"dw-p" + cssArtclPost + cutS}>
      { postTitleXml }
      { ifThen(post.loginId != PageRenderer.DummyAuthorLogin.id,
      <div class='dw-p-hd'>
        By { _linkTo(author)}{ dateAbbr(post.ctime, "dw-p-at")
        }{ flagsTop }{ ratingTagsTop }{ editInfo }{ flagsDetails
        }{ ratingTagsDetails }
      </div>
      )}
      <div class={"dw-p-bd"+ cssArtclBody}>
        <div class='dw-p-bd-blk'>
        { xmlText
        // (Don't  place a .dw-i-ts here. Splitting the -bd into
        // -bd-blks and -i-ts is better done client side, where the
        // heights of stuff is known.)
        }
        </div>
      </div>
    </div>

    RenderedComment(html = commentHtml, replyBtnText = replyBtnText,
       topRatingsText = topTagsAsText, templCmdNodes = templCmdNodes)
  }


  def _renderPageTitle(titlePost: ViPo): RenderedComment = {
    // The title is a post, itself.
    // Therefore this XML is almost identical to the XML
    // for the post that this title entitles.
    // In the future, I could make a recursive call to
    // _renderPost, to render the title. Then it would be
    // possible to reply-inline to the title.
    // (Don't wrap the <h1> in a <header>; there's no need to wrap single
    // tags in a <header>.)
    val html =
      <div id={"post-"+ titlePost.id} class='dw-p dw-p-ttl'>
        <div class='dw-p-bd'>
          <div class='dw-p-bd-blk'>
            <h1 class='dw-p-ttl'>{titlePost.text}</h1>
          </div>
        </div>
      </div>

    RenderedComment(html, replyBtnText = Nil,
      topRatingsText = None, templCmdNodes = Nil)
  }


  def _linkTo(nilo: NiLo) = HtmlPageSerializer.linkTo(nilo)

}


