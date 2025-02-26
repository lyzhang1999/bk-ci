/*
 * Tencent is pleased to support the open source community by making BK-CI 蓝鲸持续集成平台 available.
 *
 * Copyright (C) 2019 THL A29 Limited, a Tencent company.  All rights reserved.
 *
 * BK-CI 蓝鲸持续集成平台 is licensed under the MIT license.
 *
 * A copy of the MIT License is included in this file.
 *
 *
 * Terms of the MIT License:
 * ---------------------------------------------------
 * Permission is hereby granted, free of charge, to any person obtaining a copy of this software and associated documentation
 * files (the "Software"), to deal in the Software without restriction, including without limitation the rights to use, copy,
 * modify, merge, publish, distribute, sublicense, and/or sell copies of the Software, and to permit persons to whom the
 * Software is furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED, INCLUDING BUT NOT
 * LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN
 * NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY,
 * WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE
 * SOFTWARE OR THE USE OR OTHER DEALINGS IN THE SOFTWARE.
 */

package com.tencent.devops.process.pojo.code.git

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

data class GitMergeRequestEvent(
    val user: GitUser,
    val manual_unlock: Boolean? = false,
    val object_attributes: GitMRAttributes
) : GitEvent() {
    companion object {
        const val classType = "merge_request"
    }
}

@JsonIgnoreProperties(ignoreUnknown = true)
data class GitMRAttributes(
    val id: Long,
    val target_branch: String,
    val source_branch: String,
    val author_id: Long,
    val assignee_id: Long,
    val title: String,
    val created_at: String,
    val updated_at: String,
    val state: String,
    val merge_status: String,
    val target_project_id: String,
    val iid: Long,
    val description: String?,
    val source: GitProject,
    val target: GitProject,
    val last_commit: GitCommit,
    val url: String,
    val action: String,
    val extension_action: String
)
