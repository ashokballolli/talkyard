/**
 * Copyright (C) 2012-2013 Kaj Magnus Lindberg (born 1979)
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

package controllers

import actions.ApiActions._
import com.debiki.core._
import com.debiki.core.Prelude._
import debiki._
import debiki.DebikiHttp._
import debiki.onebox.Onebox
import play.api._
import play.api.libs.json._
import play.api.mvc.{Action => _, _}
import requests._
import scala.concurrent.ExecutionContext.Implicits.global
import Utils.{OkSafeJson, parseIntOrThrowBadReq}


/** Edits pages.
  *
 * SECURITY BUG I think it's possible to use edit GET/POST requests
 * to access and *read* hidden pages. I don't think I do any access control
 * when sending the current markup source back to the browser? Only when
 * actually saving something ...?
 *  -- I'm doing it *sometimes* when loading PermsOnPage via
 *  PageActions.PageReqAction?
 */
object EditController extends mvc.Controller {

  val EmptyPostErrorMessage =
    o"""Cannot save empty posts. If you want to delete this post, please click
        More just below the post, and then Delete. However only the post author
        and staff members can do this."""


  /** Sends back a post's current CommonMark source to the browser.
    */
  def loadCurrentText(pageId: String, postId: String) = GetAction { request =>
    val postIdAsInt = parseIntOrThrowBadReq(postId, "DwE1Hu80")
    val post = request.dao.loadPost(pageId, postId.toInt) getOrElse
      throwNotFound("DwE7SKE3", "Post not found")
    val json = Json.obj("currentText" -> post.currentSource)
    OkSafeJson(json)
  }


  /** Edits posts.
    */
  def edit = PostJsonAction(RateLimits.EditPost, maxLength = MaxPostSize) {
        request: JsonPostRequest =>
    val pageId = (request.body \ "pageId").as[PageId]
    val postId = (request.body \ "postId").as[PostId]
    val newText = (request.body \ "text").as[String]

    if (postId == PageParts.TitleId)
      throwForbidden("DwE5KEWF4", "Edit the title via /-/edit-title-save-settings instead")

    if (newText.isEmpty)
      throwBadReq("DwE6KEFW8", EmptyPostErrorMessage)

    _throwIfTooMuchData(newText, request)

    request.dao.editPost(pageId = pageId, postId = postId, editorId = request.theUser.id,
      request.theBrowserIdData, newText)

    OkSafeJson(ReactJson.postToJson2(postId = postId, pageId = pageId,
      request.dao, includeUnapproved = true))
  }


  /** Downloads the linked resource via an external request to the URL (assuming it's
    * a trusted safe site) then creates and returns sanitized onebox html.
    */
  def onebox(url: String) = AsyncGetActionRateLimited(RateLimits.LoadOnebox) { request =>
    Onebox.loadRenderSanitize(url, javascriptEngine = None).transform(
      html => Ok(html),
      throwable => ResultException(BadReqResult("DwE4PKE2", "Cannot onebox that link")))
  }


  def changePostType = PostJsonAction(RateLimits.EditPost, maxLength = 100) { request =>
    val pageId = (request.body \ "pageId").as[PageId]
    val postNr = (request.body \ "postNr").as[PostId]
    val newTypeInt = (request.body \ "newType").as[Int]
    val newType = PostType.fromInt(newTypeInt) getOrElse throwBadArgument("DwE4EWL3", "newType")

    request.dao.changePostType(pageId = pageId, postNr = postNr, newType,
      changerId = request.theUser.id, request.theBrowserIdData)
    Ok
  }


  def deletePost = PostJsonAction(RateLimits.DeletePost, maxLength = 5000) { request =>
    val pageId = (request.body \ "pageId").as[PageId]
    val postNr = (request.body \ "postNr").as[PostId]
    val repliesToo = (request.body \ "repliesToo").as[Boolean]

    val action =
      if (repliesToo) PostStatusAction.DeleteTree
      else PostStatusAction.DeletePost(clearFlags = false)

    request.dao.changePostStatus(postNr, pageId = pageId, action, userId = request.theUserId)

    OkSafeJson(ReactJson.postToJson2(postId = postNr, pageId = pageId, // COULD: don't include post in reply? It'd be annoying if other unrelated changes were loaded just because the post was toggled open?
      request.dao, includeUnapproved = request.theUser.isStaff))
  }


  private def _throwIfTooMuchData(text: String, request: DebikiRequest[_]) {
    val postSize = text.length
    val user = request.user_!
    if (user.isAdmin) {
      // Allow up to MaxPostSize chars (see above).
    }
    else if (user.isAuthenticated) {
      if (postSize > MaxPostSizeForAuUsers)
        throwEntityTooLarge("DwE413kX5", "Please do not upload that much text")
    }
    else {
      if (postSize > MaxPostSizeForUnauUsers)
        throwEntityTooLarge("DwE413IJ1", "Please do not upload that much text")
    }
  }

}

