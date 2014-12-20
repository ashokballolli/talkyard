/**
 * Copyright (C) 2014 Kaj Magnus Lindberg (born 1979)
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

/// <reference path="ForumModule.ts" />
/// <reference path="../../shared/plain-old-javascript.d.ts" />

//------------------------------------------------------------------------------
   module debiki2.forum {
//------------------------------------------------------------------------------


export interface OrderOffset {}

export module OrderOffsets {
  export class ByBumpTime implements OrderOffset {
    constructor(public epoch: number) {
    }
  }

  export class ByLikesAndBumpTime implements OrderOffset {
    constructor(public numLikes: number, public epoch: number) {
    }
  }
}


export class QueryService {

  private forumId: string = this.getForumId();
  private forumData: ForumData = new ForumData();


  public static $inject = ['$http', '$q', 'CategoryService'];
  constructor(private $http: ng.IHttpService, private $q: ng.IQService) {
    this.initializeCategories();
  }


  public getCategories(): Category[] {
    return _.values(this.forumData.categoriesById);
  }


  public loadTopics(categoryId: string, orderOffset: OrderOffset): ng.IPromise<Topic[]> {
    var deferred = this.$q.defer<Topic[]>();

    if (!categoryId) {
      categoryId = this.forumId;
    }

    var url = '/-/list-topics?categoryId=' + categoryId;

    if (orderOffset instanceof OrderOffsets.ByBumpTime) (() => {
      url += '&sortOrder=ByBumpTime';
      var ordOfs = <OrderOffsets.ByBumpTime> orderOffset;
      if (ordOfs.epoch) {
        url += '&epoch=' + ordOfs.epoch;
      }
    })();
    else if (orderOffset instanceof OrderOffsets.ByLikesAndBumpTime) (() => {
      url += '&sortOrder=ByLikesAndBumpTime';
      var ordOfs = <OrderOffsets.ByLikesAndBumpTime> orderOffset;
      if (ordOfs.numLikes !== null && ordOfs.epoch) {
        url += '&num=' + ordOfs.numLikes;
        url += '&epoch=' + ordOfs.epoch;
      }
    })();
    else {
      console.log('Bad orderOffset [DwE5FS0]');
      return;
    }

    this.$http.get(url).success((response: any) => {
      var topics: Topic[] = [];
      for (var i = 0; i < response.topics.length; ++i) {
        var data = response.topics[i];
        var t = Topic.fromJson(this.forumData, data);
        topics.push(t);
      }
      deferred.resolve(topics);
    });
    return deferred.promise;
  }


  /**
   * Loads all categories including descriptions and topic counts and links to
   * a few recent topics.
   */
  public loadCategoryDetails(): ng.IPromise<Category[]> {
    var deferred = this.$q.defer<Category[]>();
    this.$http.get('/-/list-categories?forumId=' + this.forumId).success((response: any) => {
      var categories: Category[] = [];
      for (var i = 0; i < response.categories.length; ++i) {
        var data = response.categories[i];
        var c = Category.fromJson(this.forumData, data);
        categories.push(c);
      }
      deferred.resolve(categories);
    });
    return deferred.promise;
  }


  /**
   * The fourm id is the same as the page id.
   */
  private getForumId(): string {
    // The page id is encoded in the HTML.
    return debiki.getPageId();
  }


  private initializeCategories() {
    var categoriesData = debiki2.ReactStore.getCategories();
    for (var i = 0; i < categoriesData.length; ++i) {
      var category: Category = Category.fromJson(this.forumData, categoriesData[i]);
      this.forumData.categoriesById[category.pageId] = category;
    }
  }

}


forumModule.service('QueryService', QueryService);

//------------------------------------------------------------------------------
   }
//------------------------------------------------------------------------------
// vim: et ts=2 sw=2 tw=0 fo=tcqwn list
