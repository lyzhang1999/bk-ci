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

package com.tencent.devops.process.engine.service

import com.fasterxml.jackson.core.JsonParseException
import com.fasterxml.jackson.databind.ObjectMapper
import com.tencent.devops.common.api.constant.CommonMessageCode
import com.tencent.devops.common.api.exception.ErrorCodeException
import com.tencent.devops.common.api.exception.OperationException
import com.tencent.devops.common.api.exception.PipelineAlreadyExistException
import com.tencent.devops.common.api.model.SQLLimit
import com.tencent.devops.common.api.model.SQLPage
import com.tencent.devops.common.api.util.PageUtil
import com.tencent.devops.common.api.util.Watcher
import com.tencent.devops.common.api.util.timestampmilli
import com.tencent.devops.common.auth.api.AuthPermission
import com.tencent.devops.common.client.Client
import com.tencent.devops.common.pipeline.Model
import com.tencent.devops.common.pipeline.container.Stage
import com.tencent.devops.common.pipeline.container.TriggerContainer
import com.tencent.devops.common.pipeline.enums.BuildStatus
import com.tencent.devops.common.pipeline.enums.ChannelCode
import com.tencent.devops.common.pipeline.enums.PipelineInstanceTypeEnum
import com.tencent.devops.common.pipeline.extend.ModelCheckPlugin
import com.tencent.devops.common.pipeline.pojo.BuildFormProperty
import com.tencent.devops.common.pipeline.pojo.BuildNo
import com.tencent.devops.common.pipeline.pojo.element.atom.BeforeDeleteParam
import com.tencent.devops.common.service.utils.LogUtils
import com.tencent.devops.common.service.utils.MessageCodeUtil
import com.tencent.devops.process.constant.ProcessMessageCode
import com.tencent.devops.process.dao.PipelineSettingDao
import com.tencent.devops.process.engine.common.VMUtils
import com.tencent.devops.process.engine.compatibility.BuildPropertyCompatibilityTools
import com.tencent.devops.process.engine.dao.PipelineBuildSummaryDao
import com.tencent.devops.process.engine.dao.PipelineInfoDao
import com.tencent.devops.process.engine.dao.template.TemplateDao
import com.tencent.devops.process.engine.dao.template.TemplatePipelineDao
import com.tencent.devops.process.engine.pojo.PipelineFilterByLabelInfo
import com.tencent.devops.process.engine.pojo.PipelineFilterParam
import com.tencent.devops.process.engine.pojo.PipelineInfo
import com.tencent.devops.process.jmx.api.ProcessJmxApi
import com.tencent.devops.process.jmx.pipeline.PipelineBean
import com.tencent.devops.process.permission.PipelinePermissionService
import com.tencent.devops.process.pojo.Pipeline
import com.tencent.devops.process.pojo.PipelineSortType
import com.tencent.devops.process.pojo.PipelineStatus
import com.tencent.devops.process.pojo.PipelineWithModel
import com.tencent.devops.process.pojo.app.PipelinePage
import com.tencent.devops.process.pojo.classify.PipelineViewAndPipelines
import com.tencent.devops.process.pojo.classify.PipelineViewFilterByCreator
import com.tencent.devops.process.pojo.classify.PipelineViewFilterByLabel
import com.tencent.devops.process.pojo.classify.PipelineViewFilterByName
import com.tencent.devops.process.pojo.classify.PipelineViewPipelinePage
import com.tencent.devops.process.pojo.classify.enums.Condition
import com.tencent.devops.process.pojo.classify.enums.Logic
import com.tencent.devops.process.pojo.pipeline.SimplePipeline
import com.tencent.devops.process.pojo.setting.PipelineRunLockType
import com.tencent.devops.process.pojo.setting.PipelineSetting
import com.tencent.devops.process.pojo.template.TemplateType
import com.tencent.devops.process.service.PipelineSettingService
import com.tencent.devops.process.service.PipelineUserService
import com.tencent.devops.process.service.label.PipelineGroupService
import com.tencent.devops.process.service.view.PipelineViewService
import com.tencent.devops.process.utils.PIPELINE_VIEW_ALL_PIPELINES
import com.tencent.devops.process.utils.PIPELINE_VIEW_FAVORITE_PIPELINES
import com.tencent.devops.process.utils.PIPELINE_VIEW_MY_PIPELINES
import com.tencent.devops.project.api.service.ServiceProjectResource
import com.tencent.devops.store.api.common.ServiceStoreResource
import org.jooq.DSLContext
import org.jooq.Record
import org.jooq.Result
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.beans.factory.annotation.Value
import org.springframework.dao.DuplicateKeyException
import org.springframework.stereotype.Service
import java.time.LocalDateTime
import java.util.Collections
import javax.ws.rs.core.Response

@Service
class PipelineService @Autowired constructor(
    private val pipelineRepositoryService: PipelineRepositoryService,
    private val pipelineRuntimeService: PipelineRuntimeService,
    private val pipelinePermissionService: PipelinePermissionService,
    private val pipelineGroupService: PipelineGroupService,
    private val pipelineViewService: PipelineViewService,
    private val pipelineUserService: PipelineUserService,
    private val pipelineSettingService: PipelineSettingService,
    private val pipelineStageService: PipelineStageService,
    private val pipelineBean: PipelineBean,
    private val processJmxApi: ProcessJmxApi,
    private val dslContext: DSLContext,
    private val templateDao: TemplateDao,
    private val templatePipelineDao: TemplatePipelineDao,
    private val pipelineInfoDao: PipelineInfoDao,
    private val pipelineSettingDao: PipelineSettingDao,
    private val pipelineBuildSummaryDao: PipelineBuildSummaryDao,
    private val modelCheckPlugin: ModelCheckPlugin,
    private val objectMapper: ObjectMapper,
    private val client: Client
) {

    @Value("\${process.deletedPipelineStoreDays:30}")
    private val deletedPipelineStoreDays: Int = 30

    companion object {
        private val logger = LoggerFactory.getLogger(PipelineService::class.java)
    }

    fun sortPipelines(pipelines: List<Pipeline>, sortType: PipelineSortType) {
        Collections.sort(pipelines) { a, b ->
            when (sortType) {
                PipelineSortType.NAME -> {
                    a.pipelineName.toLowerCase().compareTo(b.pipelineName.toLowerCase())
                }
                PipelineSortType.CREATE_TIME -> {
                    b.createTime.compareTo(a.createTime)
                }
                PipelineSortType.UPDATE_TIME -> {
                    b.deploymentTime.compareTo(a.deploymentTime)
                }
            }
        }
    }

    fun createPipeline(
        userId: String,
        projectId: String,
        model: Model,
        channelCode: ChannelCode,
        checkPermission: Boolean = true,
        fixPipelineId: String? = null,
        instanceType: String? = PipelineInstanceTypeEnum.FREEDOM.type,
        buildNo: BuildNo? = null,
        param: List<BuildFormProperty>? = null,
        tempalteVersion: Long? = null
    ): String {
        val watcher = Watcher(id = "createPipeline|$projectId|$userId|$channelCode|$checkPermission|$instanceType|$fixPipelineId")
        var success = false
        try {

            if (checkPermission) {
                watcher.start("perm_v_perm")
                pipelinePermissionService.validPipelinePermission(
                    userId = userId,
                    projectId = projectId,
                    pipelineId = "*",
                    permission = AuthPermission.CREATE,
                    message = "用户($userId)无权限在工程($projectId)下创建流水线"
                )
                watcher.stop()
            }

            if (isPipelineExist(projectId = projectId, pipelineId = fixPipelineId, name = model.name, channelCode = channelCode)) {
                logger.warn("The pipeline(${model.name}) is exist")
                throw ErrorCodeException(
                    statusCode = Response.Status.CONFLICT.statusCode,
                    errorCode = ProcessMessageCode.ERROR_PIPELINE_NAME_EXISTS,
                    defaultMessage = "流水线名称已被使用"
                )
            }

            // 检查用户是否有插件的使用权限
            if (model.srcTemplateId != null) {
                watcher.start("store_template_perm")
                val srcTemplateId = model.srcTemplateId as String
                val validateRet = client.get(ServiceStoreResource::class)
                    .validateUserTemplateAtomVisibleDept(userId = userId, templateCode = srcTemplateId, projectCode = projectId)
                if (validateRet.isNotOk()) {
                    throw OperationException(validateRet.message ?: "模版下存在无权限的插件")
                }
                watcher.stop()
            }

            watcher.start("project_v_pipeline")
            // 检查用户流水线是否达到上限
            val projectVO = client.get(ServiceProjectResource::class).get(projectId).data
            if (projectVO?.pipelineLimit != null) {
                val preCount = pipelineInfoDao.countByProjectIds(dslContext, listOf(projectId), ChannelCode.BS)
                if (preCount >= projectVO.pipelineLimit!!) {
                    throw OperationException("该项目最多只能创建${projectVO.pipelineLimit}条流水线")
                }
            }
            watcher.stop()

            var pipelineId: String? = null
            try {
                val instance = if (instanceType == PipelineInstanceTypeEnum.FREEDOM.type) {
                    // 将模版常量变更实例化为流水线变量
                    val triggerContainer = model.stages[0].containers[0] as TriggerContainer
                    instanceModel(
                        templateModel = model,
                        pipelineName = model.name,
                        buildNo = triggerContainer.buildNo,
                        param = triggerContainer.params,
                        instanceFromTemplate = false,
                        labels = model.labels
                    )
                } else {
                    model
                }
                watcher.start("deployPipeline")
                pipelineId = pipelineRepositoryService.deployPipeline(
                    model = instance,
                    projectId = projectId,
                    signPipelineId = fixPipelineId,
                    userId = userId,
                    channelCode = channelCode,
                    create = true
                )
                watcher.stop()

                // 先进行模板关联操作
                if (model.templateId != null) {
                    val templateId = model.templateId as String
                    watcher.start("createTemplate")
                    createRelationBtwTemplate(
                        userId = userId,
                        templateId = templateId,
                        pipelineId = pipelineId,
                        instanceType = instanceType!!,
                        buildNo = buildNo,
                        param = param,
                        tempalteVersion = tempalteVersion
                    )
                    watcher.stop()
                }

                // 模板关联操作成功后再创建流水线相关资源
                if (checkPermission) {
                    watcher.start("perm_c_perm")
                    try {
                        pipelinePermissionService.createResource(
                            userId = userId,
                            projectId = projectId,
                            pipelineId = pipelineId,
                            pipelineName = model.name
                        )
                    } catch (ignored: Throwable) {
                        if (fixPipelineId != pipelineId) {
                            throw ignored
                        }
                    }
                    watcher.stop()
                }
                pipelineGroupService.addPipelineLabel(userId = userId, pipelineId = pipelineId, labelIds = model.labels)
                pipelineUserService.create(pipelineId, userId)

                success = true
                return pipelineId
            } catch (duplicateKeyException: DuplicateKeyException) {
                logger.info("duplicateKeyException: ${duplicateKeyException.message}")
                if (pipelineId != null) {
                    pipelineRepositoryService.deletePipeline(projectId, pipelineId, userId, channelCode, true)
                }
                throw ErrorCodeException(
                    statusCode = Response.Status.CONFLICT.statusCode,
                    errorCode = ProcessMessageCode.ERROR_PIPELINE_IS_EXISTS,
                    defaultMessage = "流水线已经存在或未找到对应模板"
                )
            } catch (ignored: Throwable) {
                if (pipelineId != null) {
                    pipelineRepositoryService.deletePipeline(projectId, pipelineId, userId, channelCode, true)
                }
                throw ignored
            } finally {
                if (!success) {
                    val beforeDeleteParam = BeforeDeleteParam(
                        userId = userId, projectId = projectId, pipelineId = pipelineId ?: "", channelCode = channelCode
                    )
                    modelCheckPlugin.beforeDeleteElementInExistsModel(existModel = model, sourceModel = null, param = beforeDeleteParam)
                }
            }
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher)
            pipelineBean.create(success)
            processJmxApi.execute(ProcessJmxApi.NEW_PIPELINE_CREATE, watcher.totalTimeMillis)
        }
    }

    /**
     * 创建模板和流水线关联关系
     */
    fun createRelationBtwTemplate(
        userId: String,
        templateId: String,
        pipelineId: String,
        instanceType: String,
        buildNo: BuildNo? = null,
        param: List<BuildFormProperty>? = null,
        tempalteVersion: Long? = null
    ): Boolean {
        logger.info("start createRelationBtwTemplate: $userId|$templateId|$pipelineId|$instanceType")
        val latestTemplate = templateDao.getLatestTemplate(dslContext, templateId)
        var rootTemplateId = templateId
        var templateVersion = latestTemplate.version
        var versionName = latestTemplate.versionName

        if (tempalteVersion != null) {
            templateVersion = tempalteVersion
            versionName = templateDao.getTemplate(dslContext, tempalteVersion).versionName
        }

        if (latestTemplate.type == TemplateType.CONSTRAINT.name) {
            logger.info("template[$templateId] is from store, srcTemplateId is ${latestTemplate.srcTemplateId}")
            val rootTemplate = templateDao.getLatestTemplate(dslContext, latestTemplate.srcTemplateId)
            rootTemplateId = rootTemplate.id
            templateVersion = rootTemplate.version
            versionName = rootTemplate.versionName
        }

        templatePipelineDao.create(
            dslContext = dslContext,
            pipelineId = pipelineId,
            instanceType = instanceType,
            rootTemplateId = rootTemplateId,
            templateVersion = templateVersion,
            versionName = versionName,
            templateId = templateId,
            userId = userId,
            buildNo = if (buildNo == null) {
                null
            } else {
                objectMapper.writeValueAsString(buildNo)
            },
            param = if (param == null) {
                null
            } else {
                objectMapper.writeValueAsString(param)
            }
        )
        return true
    }

    /**
     * 通过流水线参数和模板来实例化流水线
     */
    fun instanceModel(
        templateModel: Model,
        pipelineName: String,
        buildNo: BuildNo?,
        param: List<BuildFormProperty>?,
        instanceFromTemplate: Boolean,
        labels: List<String>? = null
    ): Model {
        val templateTrigger = templateModel.stages[0].containers[0] as TriggerContainer
        val instanceParam = if (templateTrigger.templateParams == null) {
            mergeProperties(templateTrigger.params, param ?: emptyList())
        } else {
            mergeProperties(
                templateTrigger.params,
                mergeProperties(templateTrigger.templateParams!!, param ?: emptyList())
            )
        }

        val triggerContainer = TriggerContainer(
            id = null,
            name = templateTrigger.name,
            elements = templateTrigger.elements,
            status = null, startEpoch = null, systemElapsed = null, elementElapsed = null,
            params = instanceParam,
            templateParams = null,
            buildNo = buildNo,
            canRetry = templateTrigger.canRetry,
            containerId = templateTrigger.containerId
        )

        return Model(
            name = pipelineName,
            desc = "",
            stages = getFixedStages(templateModel, triggerContainer),
            labels = labels ?: templateModel.labels,
            instanceFromTemplate = instanceFromTemplate
        )
    }

    /**
     * 对于模板， 合并模板的参数，以流水线的为主，如果流水线的有就会覆盖模板的
     */
    fun mergeProperties(from: List<BuildFormProperty>, to: List<BuildFormProperty>): List<BuildFormProperty> {
        val result = ArrayList<BuildFormProperty>()

        from.forEach { f ->
            var override = false
            run lit@{
                to.forEach { t ->
                    if (t.id == f.id) {
                        override = true
                        return@lit
                    }
                }
            }
            if (override) {
                return@forEach
            }
            result.add(f)
        }
        result.addAll(to)
        return result
    }

    /**
     * Copy the exist pipeline
     */
    fun copyPipeline(
        userId: String,
        projectId: String,
        pipelineId: String,
        name: String,
        desc: String?,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): String {

        val pipeline = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在"
            )

        logger.info("Start to copy the pipeline $pipelineId")
        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.EDIT,
                message = "用户无流水线编辑权限"
            )
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = "*",
                permission = AuthPermission.CREATE,
                message = "用户($userId)无权限在工程($projectId)下创建流水线"
            )
        }

        if (pipeline.channelCode != channelCode) {
            throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_CHANNEL_CODE,
                defaultMessage = "指定要复制的流水线渠道来源${pipeline.channelCode}不符合$channelCode",
                params = arrayOf(pipeline.channelCode.name)
            )
        }

        val model = pipelineRepositoryService.getModel(pipelineId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
                defaultMessage = "指定要复制的流水线-模型不存在"
            )
        try {
            val copyMode = Model(name, desc ?: model.desc, model.stages)
            modelCheckPlugin.clearUpModel(copyMode)
            val newPipelineId = createPipeline(userId, projectId, copyMode, channelCode)
            val settingInfo = pipelineSettingService.getSettingInfo(projectId, pipelineId, userId)
            if (settingInfo != null) {
                // setting pipeline需替换成新流水线的
                val newSetting = pipelineSettingService.rebuildSetting(
                    oldSetting = settingInfo!!,
                    projectId = projectId,
                    newPipelineId = newPipelineId,
                    pipelineName = name
                )
                // 复制setting到新流水线
                pipelineSettingService.saveSetting(
                    userId = userId,
                    setting = newSetting
                )
            }
            return newPipelineId
        } catch (e: JsonParseException) {
            logger.error("Parse process($pipelineId) fail", e)
            throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ILLEGAL_PIPELINE_MODEL_JSON,
                defaultMessage = "非法的流水线"
            )
        } catch (e: PipelineAlreadyExistException) {
            throw ErrorCodeException(
                statusCode = Response.Status.CONFLICT.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NAME_EXISTS,
                defaultMessage = "流水线名称已被使用"
            )
        } catch (e: ErrorCodeException) {
            throw e
        } catch (e: Exception) {
            logger.warn("Fail to get the pipeline($pipelineId) definition of project($projectId)", e)
            throw ErrorCodeException(
                errorCode = ProcessMessageCode.OPERATE_PIPELINE_FAIL,
                defaultMessage = "非法的流水线",
                params = arrayOf(e.message ?: "")
            )
        }
    }

    fun editPipeline(
        userId: String,
        projectId: String,
        pipelineId: String,
        model: Model,
        channelCode: ChannelCode,
        checkPermission: Boolean = true,
        checkTemplate: Boolean = true
    ) {
        if (checkTemplate && isTemplatePipeline(pipelineId)) {
            throw ErrorCodeException(
                errorCode = ProcessMessageCode.ERROR_PIPELINE_TEMPLATE_CAN_NOT_EDIT,
                defaultMessage = "模板流水线不支持编辑"
            )
        }
        val apiStartEpoch = System.currentTimeMillis()
        var success = false
        logger.info("Start to edit the pipeline $pipelineId of project $projectId with channel $channelCode and permission $checkPermission by user $userId")
        try {
            if (checkPermission) {
                pipelinePermissionService.validPipelinePermission(
                    userId = userId,
                    projectId = projectId,
                    pipelineId = pipelineId,
                    permission = AuthPermission.EDIT,
                    message = "用户($userId)无权限在工程($projectId)下编辑流水线($pipelineId)"
                )
            }

            if (isPipelineExist(projectId = projectId, pipelineId = pipelineId, name = model.name, channelCode = channelCode)) {
                logger.warn("The pipeline(${model.name}) is exist")
                throw ErrorCodeException(
                    statusCode = Response.Status.CONFLICT.statusCode,
                    errorCode = ProcessMessageCode.ERROR_PIPELINE_NAME_EXISTS,
                    defaultMessage = "流水线名称已被使用"
                )
            }

            val pipeline = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)
                ?: throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                    defaultMessage = "流水线不存在"
                )

            if (pipeline.channelCode != channelCode) {
                throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = ProcessMessageCode.ERROR_PIPELINE_CHANNEL_CODE,
                    defaultMessage = "指定要复制的流水线渠道来源${pipeline.channelCode}不符合$channelCode",
                    params = arrayOf(pipeline.channelCode.name)
                )
            }

            val existModel = pipelineRepositoryService.getModel(pipelineId)
                ?: throw ErrorCodeException(
                    statusCode = Response.Status.NOT_FOUND.statusCode,
                    errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
                    defaultMessage = "指定要复制的流水线-模型不存在"
                )
            // 对已经存在的模型做处理
            val param = BeforeDeleteParam(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                channelCode = channelCode
            )
            modelCheckPlugin.beforeDeleteElementInExistsModel(existModel, model, param)

            pipelineRepositoryService.deployPipeline(model, projectId, pipelineId, userId, channelCode, false)
            if (checkPermission) {
                pipelinePermissionService.modifyResource(projectId, pipelineId, model.name)
            }
            pipelineUserService.update(pipelineId, userId)
            success = true
        } finally {
            pipelineBean.edit(success)
            processJmxApi.execute(ProcessJmxApi.NEW_PIPELINE_EDIT, System.currentTimeMillis() - apiStartEpoch)
        }
    }

    fun renamePipeline(
        userId: String,
        projectId: String,
        pipelineId: String,
        name: String,
        channelCode: ChannelCode
    ) {
        val pipelineModel = getPipeline(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            channelCode = channelCode
        )
        pipelineModel.name = name
        editPipeline(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            model = pipelineModel,
            channelCode = channelCode,
            checkPermission = true,
            checkTemplate = false
        )
        val pipelineDesc = pipelineInfoDao.getPipelineInfo(
            dslContext = dslContext,
            pipelineId = pipelineId,
            channelCode = channelCode
        )?.pipelineDesc
        pipelineSettingDao.updateSetting(
            dslContext = dslContext,
            pipelineId = pipelineId,
            name = name,
            desc = pipelineDesc ?: ""
        )
        pipelineInfoDao.update(
            dslContext = dslContext,
            pipelineId = pipelineId,
            userId = userId,
            updateVersion = false,
            pipelineName = name,
            pipelineDesc = pipelineDesc
        )
    }

    fun saveAll(
        userId: String,
        projectId: String,
        pipelineId: String,
        model: Model,
        setting: PipelineSetting,
        channelCode: ChannelCode,
        checkPermission: Boolean = true,
        checkTemplate: Boolean = true
    ) {
        editPipeline(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            model = model,
            channelCode = channelCode,
            checkPermission = checkPermission,
            checkTemplate = checkTemplate
        )
        pipelineSettingService.saveSetting(userId, setting, false)
    }

    fun saveSetting(
        userId: String,
        projectId: String,
        pipelineId: String,
        setting: PipelineSetting,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ) {
        val pipelineModel = getPipeline(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            channelCode = channelCode
        )
        editPipeline(
            userId = userId,
            projectId = projectId,
            pipelineId = pipelineId,
            model = pipelineModel,
            channelCode = channelCode,
            checkPermission = checkPermission,
            checkTemplate = false
        )
        pipelineSettingService.saveSetting(userId, setting, false)
    }

    fun getPipeline(
        userId: String,
        projectId: String,
        pipelineId: String,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): Model {

        if (checkPermission) {
            pipelinePermissionService.validPipelinePermission(
                userId = userId,
                projectId = projectId,
                pipelineId = pipelineId,
                permission = AuthPermission.VIEW,
                message = "用户($userId)无权限在工程($projectId)下获取流水线($pipelineId)"
            )
        }

        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(projectId, pipelineId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_NOT_EXISTS,
                defaultMessage = "流水线不存在"
            )

        if (pipelineInfo.channelCode != channelCode) {
            throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_CHANNEL_CODE,
                defaultMessage = "指定要复制的流水线渠道来源${pipelineInfo.channelCode}不符合$channelCode",
                params = arrayOf(pipelineInfo.channelCode.name)
            )
        }

        val model = pipelineRepositoryService.getModel(pipelineId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_PIPELINE_MODEL_NOT_EXISTS,
                defaultMessage = "指定要复制的流水线-模型不存在"
            )

        try {
            val triggerContainer = model.stages[0].containers[0] as TriggerContainer
            val buildNo = triggerContainer.buildNo
            if (buildNo != null) {
                buildNo.buildNo = pipelineRepositoryService.getBuildNo(projectId = projectId, pipelineId = pipelineId)
                    ?: buildNo.buildNo
            }
            // 兼容性处理
            BuildPropertyCompatibilityTools.fix(triggerContainer.params)

            // 获取流水线labels
            val groups = pipelineGroupService.getGroups(userId = userId, projectId = projectId, pipelineId = pipelineId)
            val labels = mutableListOf<String>()
            groups.forEach {
                labels.addAll(it.labels)
            }
            model.labels = labels
            model.name = pipelineInfo.pipelineName
            model.desc = pipelineInfo.pipelineDesc
            model.pipelineCreator = pipelineInfo.creator

            val defaultTagIds = listOf(pipelineStageService.getDefaultStageTagId())
            model.stages.forEach {
                if (it.name.isNullOrBlank()) it.name = it.id
                if (it.tag == null) it.tag = defaultTagIds
            }

            // 部分老的模板实例没有templateId，需要手动加上
            if (model.instanceFromTemplate == true && model.templateId.isNullOrBlank()) {
                val record = templatePipelineDao.get(dslContext, pipelineId)
                model.templateId = record?.templateId
            }

            return model
        } catch (e: Exception) {
            logger.warn("Fail to get the pipeline($pipelineId) definition of project($projectId)", e)
            throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.OPERATE_PIPELINE_FAIL,
                defaultMessage = "Fail to get the pipeline",
                params = arrayOf(e.message ?: "unknown")
            )
        }
    }

    fun getBatchPipelinesWithModel(
        userId: String,
        projectId: String,
        pipelineIds: List<String>,
        channelCode: ChannelCode,
        checkPermission: Boolean = true
    ): List<PipelineWithModel> {
        if (checkPermission) {
            val permission = AuthPermission.VIEW
            val hasViewPermission = pipelinePermissionService.checkPipelinePermission(
                userId = userId,
                projectId = projectId,
                permission = permission
            )
            if (!hasViewPermission) {
                val permissionMsg = MessageCodeUtil.getCodeLanMessage(
                    messageCode = "${CommonMessageCode.MSG_CODE_PERMISSION_PREFIX}${permission.value}",
                    defaultMessage = permission.alias
                )
                throw ErrorCodeException(
                    statusCode = Response.Status.FORBIDDEN.statusCode,
                    errorCode = ProcessMessageCode.USER_NEED_PIPELINE_X_PERMISSION,
                    defaultMessage = "用户($userId)无权限在工程($projectId)下获取流水线",
                    params = arrayOf(permissionMsg)
                )
            }
        }
        return pipelineRepositoryService.getPipelinesWithLastestModels(projectId, pipelineIds, channelCode)
    }

    fun deletePipeline(
        userId: String,
        projectId: String,
        pipelineId: String,
        channelCode: ChannelCode? = null,
        checkPermission: Boolean = true,
        delete: Boolean = false
    ) {
        val watcher = Watcher(id = "deletePipeline|$pipelineId|$userId")
        var success = false
        try {
            if (checkPermission) {
                watcher.start("perm_v_perm")
                pipelinePermissionService.validPipelinePermission(
                    userId = userId, projectId = projectId, pipelineId = pipelineId,
                    permission = AuthPermission.DELETE, message = "用户($userId)无权限在工程($projectId)下删除流水线($pipelineId)"
                )
                watcher.stop()
            }

            watcher.start("s_r_pipeline_del")
            pipelineRepositoryService.deletePipeline(projectId = projectId, pipelineId = pipelineId, userId = userId, channelCode = channelCode, delete = delete)
            templatePipelineDao.delete(dslContext = dslContext, pipelineId = pipelineId)
            watcher.stop()

            if (checkPermission) {
                watcher.start("perm_d_perm")
                pipelinePermissionService.deleteResource(projectId = projectId, pipelineId = pipelineId)
                watcher.stop()
            }
            success = true
        } finally {
            watcher.stop()
            LogUtils.printCostTimeWE(watcher, warnThreshold = 2000)
            pipelineBean.delete(success)
            processJmxApi.execute(ProcessJmxApi.NEW_PIPELINE_DELETE, watcher.totalTimeMillis)
        }
    }

    fun listPipelineInfo(userId: String, projectId: String, pipelineIdList: List<String>?): List<Pipeline> {
        val pipelines = listPermissionPipeline(
            userId = userId,
            projectId = projectId,
            page = null,
            pageSize = null,
            sortType = PipelineSortType.CREATE_TIME,
            channelCode = ChannelCode.BS,
            checkPermission = false
        )

        return if (pipelineIdList == null) {
            pipelines.records
        } else {
            pipelines.records.filter { pipelineIdList.contains(it.pipelineId) }
        }
    }

    fun listPipelineInfo(
        userId: String,
        projectId: String,
        pipelineIdList: Collection<String>?,
        templateIdList: Collection<String>? = null
    ): List<Pipeline> {
        val resultPipelineIds = mutableSetOf<String>()

        val pipelines = listPermissionPipeline(
            userId = userId,
            projectId = projectId,
            page = null,
            pageSize = null,
            sortType = PipelineSortType.CREATE_TIME,
            channelCode = ChannelCode.BS,
            checkPermission = false
        )

        if (pipelineIdList != null) {
            resultPipelineIds.addAll(pipelineIdList)
        }

        if (templateIdList != null) {
            val templatePipelineIds =
                templatePipelineDao.listPipeline(dslContext, PipelineInstanceTypeEnum.CONSTRAINT.type, templateIdList)
                    .map { it.pipelineId }
            resultPipelineIds.addAll(templatePipelineIds)
        }

        return if (resultPipelineIds.isEmpty()) {
            pipelines.records
        } else {
            pipelines.records.filter { it.pipelineId in resultPipelineIds }
        }
    }

    fun listPermissionPipeline(
        userId: String,
        projectId: String,
        page: Int?,
        pageSize: Int?,
        sortType: PipelineSortType,
        channelCode: ChannelCode,
        checkPermission: Boolean
    ): PipelinePage<Pipeline> {

        val watcher = Watcher(id = "listPermissionPipeline|$projectId|$userId")
        try {

            val hasCreatePermission =
                if (!checkPermission) {
                    true
                } else {
                    watcher.start("checkPerm")
                    val validateUserResourcePermission = pipelinePermissionService.checkPipelinePermission(
                        userId = userId, projectId = projectId, permission = AuthPermission.CREATE)
                    watcher.stop()
                    validateUserResourcePermission
                }

            val pageNotNull = page ?: 0
            val pageSizeNotNull = pageSize ?: -1
            var slqLimit: SQLLimit? = null
            val hasPermissionList = if (checkPermission) {
                watcher.start("perm_r_perm")
                val hasPermissionList = pipelinePermissionService.getResourceByPermission(
                    userId = userId, projectId = projectId, permission = AuthPermission.LIST
                )
                watcher.stop()
                if (hasPermissionList.isEmpty()) {
                    return PipelinePage(
                        page = pageNotNull,
                        pageSize = pageSizeNotNull,
                        count = 0,
                        records = emptyList(),
                        hasCreatePermission = hasCreatePermission,
                        hasPipelines = false,
                        hasFavorPipelines = false,
                        hasPermissionPipelines = false,
                        currentView = null
                    )
                }
                hasPermissionList
            } else {
                emptyList()
            }

            watcher.start("s_r_summary")
            val pipelineBuildSummary =
                pipelineRuntimeService.getBuildSummaryRecords(
                    projectId = projectId, channelCode = channelCode, pipelineIds = hasPermissionList
                )
            watcher.stop()

            if (pageSizeNotNull != -1) slqLimit = PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull)

            val offset = slqLimit?.offset ?: 0
            val limit = slqLimit?.limit ?: -1
            var hasFavorPipelines = false
            val hasPermissionPipelines = hasPermissionList.isNotEmpty()

            val allPipelines = mutableListOf<Pipeline>()

            watcher.start("s_r_fav")
            if (pipelineBuildSummary.isNotEmpty) {
                val favorPipelines = pipelineGroupService.getFavorPipelines(userId = userId, projectId = projectId)
                hasFavorPipelines = favorPipelines.isNotEmpty()

                val pipelines = buildPipelines(pipelineBuildSummary = pipelineBuildSummary, favorPipelines = favorPipelines, authPipelines = hasPermissionList)
                allPipelines.addAll(pipelines)

                sortPipelines(allPipelines, sortType)
            }

            val toIndex =
                if (limit == -1 || allPipelines.size <= (offset + limit)) allPipelines.size else offset + limit
            val pagePipelines =
                if (offset >= allPipelines.size) listOf<Pipeline>() else allPipelines.subList(offset, toIndex)

            watcher.stop()

            return PipelinePage(
                page = pageNotNull,
                pageSize = pageSizeNotNull,
                count = allPipelines.size.toLong(),
                records = pagePipelines,
                hasCreatePermission = hasCreatePermission,
                hasPipelines = true,
                hasFavorPipelines = hasFavorPipelines,
                hasPermissionPipelines = hasPermissionPipelines,
                currentView = null
            )
        } finally {
            LogUtils.printCostTimeWE(watcher)
            processJmxApi.execute(ProcessJmxApi.LIST_APP_PIPELINES, watcher.totalTimeMillis)
        }
    }

    fun hasPermissionList(
        userId: String,
        projectId: String,
        authPermission: AuthPermission,
        excludePipelineId: String?,
        page: Int?,
        pageSize: Int?
    ): SQLPage<Pipeline> {

        val watcher = Watcher(id = "hasPermissionList|$projectId|$userId")
        try {
            watcher.start("perm_r_perm")
            val hasPermissionList = pipelinePermissionService.getResourceByPermission(
                userId = userId, projectId = projectId, permission = authPermission
            ).toMutableList()
            watcher.stop()
            watcher.start("s_r_summary")
            if (excludePipelineId != null) {
                // 移除需排除的流水线ID
                hasPermissionList.remove(excludePipelineId)
            }
            val pipelineBuildSummary =
                pipelineRuntimeService.getBuildSummaryRecords(
                    projectId = projectId,
                    channelCode = ChannelCode.BS,
                    pipelineIds = hasPermissionList,
                    page = page,
                    pageSize = pageSize
                )
            watcher.stop()

            watcher.start("s_r_fav")
            val count = pipelineBuildSummary.size + 0L
            val pagePipelines =
                if (count > 0) {
                    val favorPipelines = pipelineGroupService.getFavorPipelines(userId = userId, projectId = projectId)
                    buildPipelines(
                        pipelineBuildSummary = pipelineBuildSummary,
                        favorPipelines = favorPipelines,
                        authPipelines = emptyList()
                    )
                } else {
                    mutableListOf()
                }

            watcher.stop()
            return SQLPage(count = count, records = pagePipelines)
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher)
            processJmxApi.execute(ProcessJmxApi.LIST_APP_PIPELINES, watcher.totalTimeMillis)
        }
    }

    /**
     * 根据视图ID获取流水线编
     * 其中 PIPELINE_VIEW_FAVORITE_PIPELINES，PIPELINE_VIEW_MY_PIPELINES，PIPELINE_VIEW_ALL_PIPELINES
     * 分别对应 我的收藏，我的流水线，全部流水线
     */
    fun listViewPipelines(
        userId: String,
        projectId: String,
        page: Int?,
        pageSize: Int?,
        sortType: PipelineSortType,
        channelCode: ChannelCode,
        viewId: String,
        checkPermission: Boolean = true,
        filterByPipelineName: String? = null,
        filterByCreator: String? = null,
        filterByLabels: String? = null,
        callByApp: Boolean? = false,
        authPipelineIds: List<String> = emptyList(),
        skipPipelineIds: List<String> = emptyList()
    ): PipelineViewPipelinePage<Pipeline> {
        val watcher = Watcher(id = "listViewPipelines|$projectId|$userId")
        watcher.start("perm_r_perm")
        val authPipelines = if (authPipelineIds.isEmpty()) {
            pipelinePermissionService.getResourceByPermission(
                userId = userId, projectId = projectId, permission = AuthPermission.LIST
            )
        } else {
            authPipelineIds
        }
        watcher.stop()

        watcher.start("s_r_summary")
        try {
            val favorPipelines = pipelineGroupService.getFavorPipelines(userId = userId, projectId = projectId)
            val (filterByPipelineNames, filterByPipelineCreators, filterByPipelineLabels) =
                generatePipelineFilterInfo(filterByName = filterByPipelineName, filterByCreator = filterByCreator, filterByLabels = filterByLabels)
            val pipelineFilterParamList = mutableListOf<PipelineFilterParam>()
            val pipelineFilterParam = PipelineFilterParam(
                logic = Logic.AND,
                filterByPipelineNames = filterByPipelineNames,
                filterByPipelineCreators = filterByPipelineCreators,
                filterByLabelInfo = PipelineFilterByLabelInfo(
                    filterByLabels = filterByPipelineLabels,
                    labelToPipelineMap = generateLabelToPipelineMap(filterByPipelineLabels)
                )
            )
            pipelineFilterParamList.add(pipelineFilterParam)
            val viewIdList =
                listOf(PIPELINE_VIEW_FAVORITE_PIPELINES, PIPELINE_VIEW_MY_PIPELINES, PIPELINE_VIEW_ALL_PIPELINES)
            if (!viewIdList.contains(viewId)) {
                val view = pipelineViewService.getView(userId = userId, projectId = projectId, viewId = viewId)
                val filters = pipelineViewService.getFilters(view)
                val pipelineViewFilterParam = PipelineFilterParam(
                    logic = view.logic,
                    filterByPipelineNames = filters.first,
                    filterByPipelineCreators = filters.second,
                    filterByLabelInfo = PipelineFilterByLabelInfo(
                        filterByLabels = filters.third,
                        labelToPipelineMap = generateLabelToPipelineMap(filters.third)
                    )
                )
                pipelineFilterParamList.add(pipelineViewFilterParam)
            }
            pipelineViewService.addUsingView(userId = userId, projectId = projectId, viewId = viewId)

            // 查询有权限查看的流水线总数
            val totalAvailablePipelineSize = pipelineBuildSummaryDao.listPipelineInfoBuildSummaryCount(
                dslContext = dslContext,
                projectId = projectId,
                channelCode = channelCode,
                pipelineIds = authPipelines,
                favorPipelines = favorPipelines,
                authPipelines = authPipelines,
                viewId = viewId,
                pipelineFilterParamList = pipelineFilterParamList,
                permissionFlag = true
            )

            // 查询无权限查看的流水线总数
            val totalInvalidPipelineSize = pipelineBuildSummaryDao.listPipelineInfoBuildSummaryCount(
                dslContext = dslContext,
                projectId = projectId,
                channelCode = channelCode,
                favorPipelines = favorPipelines,
                authPipelines = authPipelines,
                viewId = viewId,
                pipelineFilterParamList = pipelineFilterParamList,
                permissionFlag = false
            )
            val pipelineList = mutableListOf<Pipeline>()
            val totalSize = totalAvailablePipelineSize + totalInvalidPipelineSize
            if ((null != page && null != pageSize) && !(page == 1 && pageSize == -1)) {
                // 判断可用的流水线是否已到最后一页
                val totalAvailablePipelinePage = PageUtil.calTotalPage(pageSize, totalAvailablePipelineSize)
                if (page < totalAvailablePipelinePage) {
                    // 当前页未到可用流水线最后一页，不需要处理临界点（最后一页）的情况
                    handlePipelineQueryList(
                        pipelineList = pipelineList,
                        projectId = projectId,
                        channelCode = channelCode,
                        sortType = sortType,
                        favorPipelines = favorPipelines,
                        authPipelines = authPipelines,
                        viewId = viewId,
                        pipelineFilterParamList = pipelineFilterParamList,
                        permissionFlag = true,
                        page = page,
                        pageSize = pageSize
                    )
                } else if (page == totalAvailablePipelinePage && totalAvailablePipelineSize > 0) {
                    //  查询可用流水线最后一页不满页的数量
                    val lastPageRemainNum = pageSize - totalAvailablePipelineSize % pageSize
                    handlePipelineQueryList(
                        pipelineList = pipelineList,
                        projectId = projectId,
                        channelCode = channelCode,
                        sortType = sortType,
                        favorPipelines = favorPipelines,
                        authPipelines = authPipelines,
                        viewId = viewId,
                        pipelineFilterParamList = pipelineFilterParamList,
                        permissionFlag = true,
                        page = page,
                        pageSize = pageSize
                    )
                    // 可用流水线最后一页不满页的数量需用不可用的流水线填充
                    if (lastPageRemainNum > 0 && totalInvalidPipelineSize > 0) {
                        handlePipelineQueryList(
                            pipelineList = pipelineList,
                            projectId = projectId,
                            channelCode = channelCode,
                            sortType = sortType,
                            favorPipelines = favorPipelines,
                            authPipelines = authPipelines,
                            viewId = viewId,
                            pipelineFilterParamList = pipelineFilterParamList,
                            permissionFlag = false,
                            page = 1,
                            pageSize = lastPageRemainNum.toInt()
                        )
                    }
                } else {
                    // 当前页大于可用流水线最后一页，需要排除掉可用流水线最后一页不满页的数量用不可用的流水线填充的情况
                    val lastPageRemainNum =
                        if (totalAvailablePipelineSize > 0) pageSize - totalAvailablePipelineSize % pageSize else 0
                    handlePipelineQueryList(
                        pipelineList = pipelineList,
                        projectId = projectId,
                        channelCode = channelCode,
                        sortType = sortType,
                        favorPipelines = favorPipelines,
                        authPipelines = authPipelines,
                        viewId = viewId,
                        pipelineFilterParamList = pipelineFilterParamList,
                        permissionFlag = false,
                        page = page - totalAvailablePipelinePage,
                        pageSize = pageSize,
                        offsetNum = lastPageRemainNum.toInt()
                    )
                }
            } else {
                // 不分页查询
                handlePipelineQueryList(
                    pipelineList = pipelineList,
                    projectId = projectId,
                    channelCode = channelCode,
                    sortType = sortType,
                    favorPipelines = favorPipelines,
                    authPipelines = authPipelines,
                    viewId = viewId,
                    pipelineFilterParamList = pipelineFilterParamList,
                    permissionFlag = true,
                    page = page,
                    pageSize = pageSize
                )
                handlePipelineQueryList(
                    pipelineList = pipelineList,
                    projectId = projectId,
                    channelCode = channelCode,
                    sortType = sortType,
                    favorPipelines = favorPipelines,
                    authPipelines = authPipelines,
                    viewId = viewId,
                    pipelineFilterParamList = pipelineFilterParamList,
                    permissionFlag = false,
                    page = page,
                    pageSize = pageSize
                )
            }
            watcher.stop()

            return PipelineViewPipelinePage(
                page = page ?: 1,
                pageSize = pageSize ?: totalSize.toInt(),
                count = totalSize,
                records = pipelineList
            )
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher)
            processJmxApi.execute(ProcessJmxApi.LIST_NEW_PIPELINES, watcher.totalTimeMillis)
        }
    }

    private fun handlePipelineQueryList(
        pipelineList: MutableList<Pipeline>,
        projectId: String,
        channelCode: ChannelCode,
        sortType: PipelineSortType? = null,
        pipelineIds: Collection<String>? = null,
        favorPipelines: List<String> = emptyList(),
        authPipelines: List<String> = emptyList(),
        viewId: String? = null,
        pipelineFilterParamList: List<PipelineFilterParam>? = null,
        permissionFlag: Boolean? = null,
        page: Int? = null,
        pageSize: Int? = null,
        offsetNum: Int? = 0
    ) {
        val pipelineRecords = pipelineBuildSummaryDao.listPipelineInfoBuildSummary(
            dslContext = dslContext,
            projectId = projectId,
            channelCode = channelCode,
            sortType = sortType,
            pipelineIds = pipelineIds,
            favorPipelines = favorPipelines,
            authPipelines = authPipelines,
            viewId = viewId,
            pipelineFilterParamList = pipelineFilterParamList,
            permissionFlag = permissionFlag,
            page = page,
            pageSize = pageSize,
            offsetNum = offsetNum
        )
        pipelineList.addAll(buildPipelines(pipelineRecords, favorPipelines, authPipelines))
    }

    private fun generateLabelToPipelineMap(filterByPipelineLabels: List<PipelineViewFilterByLabel>): Map<String, List<String>>? {
        var labelToPipelineMap: Map<String, List<String>>? = null
        if (filterByPipelineLabels.isNotEmpty()) {
            val labelIds = mutableListOf<String>()
            filterByPipelineLabels.forEach {
                labelIds.addAll(it.labelIds)
            }
            labelToPipelineMap = pipelineGroupService.getViewLabelToPipelinesMap(labelIds)
        }
        return labelToPipelineMap
    }

    fun filterViewPipelines(
        userId: String,
        projectId: String,
        pipelines: List<Pipeline>,
        viewId: String
    ): List<Pipeline> {
        val view = pipelineViewService.getView(userId = userId, projectId = projectId, viewId = viewId)
        val filters = pipelineViewService.getFilters(view)

        return filterViewPipelines(pipelines, view.logic, filters.first, filters.second, filters.third)
    }

    /**
     * 视图的基础上增加简单过滤
     */
    fun filterViewPipelines(
        pipelines: List<Pipeline>,
        filterByName: String?,
        filterByCreator: String?,
        filterByLabels: String?
    ): List<Pipeline> {
        logger.info("filter view pipelines $filterByName $filterByCreator $filterByLabels")

        val (filterByPipelineNames, filterByPipelineCreators, filterByPipelineLabels) = generatePipelineFilterInfo(
            filterByName,
            filterByCreator,
            filterByLabels
        )

        if (filterByPipelineNames.isEmpty() && filterByPipelineCreators.isEmpty() && filterByPipelineLabels.isEmpty()) {
            return pipelines
        }

        return filterViewPipelines(
            pipelines,
            Logic.AND,
            filterByPipelineNames,
            filterByPipelineCreators,
            filterByPipelineLabels
        )
    }

    private fun generatePipelineFilterInfo(
        filterByName: String?,
        filterByCreator: String?,
        filterByLabels: String?
    ): Triple<List<PipelineViewFilterByName>, List<PipelineViewFilterByCreator>, List<PipelineViewFilterByLabel>> {
        val filterByPipelineNames = if (filterByName.isNullOrEmpty()) {
            emptyList()
        } else {
            listOf(PipelineViewFilterByName(Condition.LIKE, filterByName!!))
        }

        val filterByPipelineCreators = if (filterByCreator.isNullOrEmpty()) {
            emptyList()
        } else {
            listOf(PipelineViewFilterByCreator(Condition.INCLUDE, filterByCreator!!.split(",")))
        }

        val filterByPipelineLabels = if (filterByLabels.isNullOrEmpty()) {
            emptyList()
        } else {
            val labelIds = filterByLabels!!.split(",")
            val labelGroupToLabelMap = pipelineGroupService.getGroupToLabelsMap(labelIds)

            labelGroupToLabelMap.map {
                PipelineViewFilterByLabel(Condition.INCLUDE, it.key, it.value)
            }
        }
        return Triple(filterByPipelineNames, filterByPipelineCreators, filterByPipelineLabels)
    }

    /**
     * 视图过滤
     */
    private fun filterViewPipelines(
        pipelines: List<Pipeline>,
        logic: Logic,
        filterByPipelineNames: List<PipelineViewFilterByName>,
        filterByPipelineCreators: List<PipelineViewFilterByCreator>,
        filterByLabels: List<PipelineViewFilterByLabel>
    ): List<Pipeline> {
        logger.info("filter view pipelines logic($logic) $filterByPipelineNames $filterByPipelineCreators $filterByLabels")

        // filter by pipeline name
        val nameFilterPipelines = if (filterByPipelineNames.isEmpty()) {
            if (logic == Logic.AND) pipelines else emptyList()
        } else {
            pipelines.filter { pipeline ->
                val name = pipeline.pipelineName
                var filter: Boolean = (logic == Logic.AND)
                filterByPipelineNames.forEach {
                    val result = it.condition.filter(it.pipelineName, name)
                    filter = if (logic == Logic.AND) {
                        filter && result
                    } else {
                        filter || result
                    }
                }
                filter
            }
        }
        logger.info("Filter by name size(${nameFilterPipelines.size})")

        if (logic == Logic.AND && nameFilterPipelines.isEmpty()) {
            logger.info("All pipelines filter by name $filterByPipelineNames")
            return nameFilterPipelines
        }

        // filter by creator, creator's condition is only include
        val creatorFilterPipelines = if (filterByPipelineCreators.isEmpty()) {
            if (logic == Logic.AND) pipelines else emptyList()
        } else {
            val pipelineIds = pipelines.map { it.pipelineId }.toSet()
            val pipelineUsers = pipelineUserService.listCreateUsers(pipelineIds)

            pipelines.filter { pipeline ->
                val user = pipelineUsers[pipeline.pipelineId]
                if (user == null) {
                    true
                } else {
                    var filter: Boolean = (logic == Logic.AND)

                    filterByPipelineCreators.forEach { filterByCreator ->
                        val result = filterByCreator.userIds.contains(user)

                        filter = if (logic == Logic.AND) {
                            filter && result
                        } else {
                            filter || result
                        }
                    }
                    filter
                }
            }
        }
        logger.info("Filter by creator size(${creatorFilterPipelines.size})")

        if (logic == Logic.AND && creatorFilterPipelines.isEmpty()) {
            logger.info("All pipelines filter by creator $filterByPipelineCreators")
            return creatorFilterPipelines
        }

        // filter by label, label's condition is only include
        val labelIds = mutableListOf<String>()
        filterByLabels.forEach {
            labelIds.addAll(it.labelIds)
        }
        val labelFilterPipelines = if (filterByLabels.isEmpty()) {
            if (logic == Logic.AND) pipelines else emptyList()
        } else {
            val labelToPipelineMap = pipelineGroupService.getViewLabelToPipelinesMap(labelIds)

            pipelines.filter { pipeline ->
                val pipelineId = pipeline.pipelineId
                var filter: Boolean = (logic == Logic.AND)

                filterByLabels.forEach { filterByLabel ->
                    var result = false

                    filterByLabel.labelIds.forEach {
                        val labelPipelineIds = labelToPipelineMap[it]
                        if (labelPipelineIds != null) {
                            result = result || labelPipelineIds.contains(pipelineId)
                        }
                    }

                    filter = if (logic == Logic.AND) {
                        filter && result
                    } else {
                        filter || result
                    }
                }
                filter
            }
        }
        logger.info("Filter by label size(${labelFilterPipelines.size})")

        if (logic == Logic.AND && labelFilterPipelines.isEmpty()) {
            logger.info("All pipelines filter by label $filterByLabels")
            return labelFilterPipelines
        }

        return if (logic == Logic.AND) {
            nameFilterPipelines
                .intersect(creatorFilterPipelines.asIterable())
                .intersect(labelFilterPipelines.asIterable())
                .toList()
        } else {
            nameFilterPipelines
                .union(creatorFilterPipelines.asIterable())
                .union(labelFilterPipelines.asIterable())
                .toList()
        }
    }

    fun listViewAndPipelines(
        userId: String,
        projectId: String,
        page: Int?,
        pageSize: Int?
    ): PipelineViewAndPipelines {
        val currentViewIdAndViewList = pipelineViewService.getCurrentViewIdAndList(userId, projectId)
        val currentViewId = currentViewIdAndViewList.first
        val currentViewList = currentViewIdAndViewList.second

        val pipelinePage = listViewPipelines(
            userId = userId,
            projectId = projectId,
            page = page,
            pageSize = pageSize,
            sortType = PipelineSortType.CREATE_TIME,
            channelCode = ChannelCode.BS,
            viewId = currentViewId,
            checkPermission = true
        )

        return PipelineViewAndPipelines(currentViewId, currentViewList, pipelinePage)
    }

    fun listPipelines(projectId: Set<String>, channelCode: ChannelCode): List<Pipeline> {

        val watcher = Watcher(id = "listPipelines|${projectId.size}")
        val pipelines = mutableListOf<Pipeline>()
        try {
            watcher.start("s_s_r_summary")
            projectId.forEach { project_id ->
                val pipelineBuildSummary = pipelineRuntimeService.getBuildSummaryRecords(project_id, channelCode)
                if (pipelineBuildSummary.isNotEmpty)
                    pipelines.addAll(buildPipelines(pipelineBuildSummary, emptyList(), emptyList()))
            }
            watcher.stop()
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher)
        }
        return pipelines
    }

    fun getPipelineInfoNum(
        dslContext: DSLContext,
        projectIds: Set<String>?,
        channelCodes: Set<ChannelCode>?
    ): Int? {
        return pipelineInfoDao.getPipelineInfoNum(dslContext, projectIds, channelCodes)!!.value1()
    }

    fun listPagedPipelines(
        dslContext: DSLContext,
        projectIds: Set<String>,
        channelCodes: Set<ChannelCode>?,
        limit: Int?,
        offset: Int?
    ): MutableList<Pipeline> {
        val watcher = Watcher(id = "listPagedPipelines|${projectIds.size}")
        val pipelines = mutableListOf<Pipeline>()
        try {
            watcher.start("s_s_r_summary")
            val pipelineBuildSummary =
                pipelineRuntimeService.getBuildSummaryRecords(dslContext, projectIds, channelCodes, limit, offset)
            if (pipelineBuildSummary.isNotEmpty)
                pipelines.addAll(buildPipelines(pipelineBuildSummary, emptyList(), emptyList()))
            watcher.stop()
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher)
        }
        return pipelines
    }

    fun listPipelinesByIds(channelCodes: Set<ChannelCode>?, pipelineIds: Set<String>): List<Pipeline> {
        val watcher = Watcher(id = "listPipelinesByIds|${pipelineIds.size}")
        val pipelines = mutableListOf<Pipeline>()
        try {
            watcher.start("s_s_r_summary")
            val pipelineBuildSummary = pipelineRuntimeService.getBuildSummaryRecords(channelCodes, pipelineIds)
            if (pipelineBuildSummary.isNotEmpty)
                pipelines.addAll(buildPipelines(pipelineBuildSummary, emptyList(), emptyList()))
            watcher.stop()
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher)
        }
        return pipelines
    }

    fun isPipelineRunning(projectId: String, buildId: String, channelCode: ChannelCode): Boolean {
        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
        return buildInfo != null && buildInfo.status == BuildStatus.RUNNING
    }

    fun isRunning(projectId: String, buildId: String, channelCode: ChannelCode): Boolean {
        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
        return buildInfo != null && BuildStatus.isRunning(buildInfo.status)
    }

    fun isPipelineExist(
        projectId: String,
        pipelineId: String? = null,
        name: String,
        channelCode: ChannelCode
    ): Boolean {
        return pipelineRepositoryService.isPipelineExist(projectId, name, channelCode, pipelineId)
    }

    fun getPipelineStatus(userId: String, projectId: String, pipelines: Set<String>): List<Pipeline> {
        val watcher = Watcher(id = "getPipelineStatus|$projectId|$userId|${pipelines.size}")
        val channelCode = ChannelCode.BS
        try {
            watcher.start("s_r_summary")
            val pipelineBuildSummary = pipelineRuntimeService.getBuildSummaryRecords(
                projectId = projectId,
                channelCode = channelCode,
                pipelineIds = pipelines
            )
            watcher.start("perm_r_perm")
            val pipelinesPermissions = pipelinePermissionService.getResourceByPermission(
                userId = userId, projectId = projectId, permission = AuthPermission.LIST
            )
            watcher.start("s_r_fav")
            val favorPipelines = pipelineGroupService.getFavorPipelines(userId, projectId)
            val pipelineList = buildPipelines(pipelineBuildSummary, favorPipelines, pipelinesPermissions)
            sortPipelines(pipelineList, PipelineSortType.UPDATE_TIME)
            watcher.stop()
            return pipelineList
        } finally {
            processJmxApi.execute(ProcessJmxApi.LIST_NEW_PIPELINES_STATUS, watcher.totalTimeMillis)
            LogUtils.printCostTimeWE(watcher = watcher)
        }
    }

    // 获取单条流水线的运行状态
    fun getSinglePipelineStatus(userId: String, projectId: String, pipeline: String): Pipeline? {
        val pipelines = setOf(pipeline)
        val pipelineList = getPipelineStatus(userId, projectId, pipelines)
        return if (pipelineList.isEmpty()) {
            null
        } else {
            pipelineList[0]
        }
    }

    // 获取单条流水线的运行状态
    fun getSinglePipelineStatusNew(userId: String, projectId: String, pipeline: String): PipelineStatus? {
        val watcher = Watcher(id = "getSinglePipelineStatusNew|$projectId|$userId|$pipeline")
        try {
            watcher.start("s_r_summary")
            val pipelineBuildRecord = pipelineRuntimeService.getBuildSummaryRecords(
                projectId = projectId,
                channelCode = ChannelCode.BS,
                pipelineIds = arrayListOf(pipeline)
            )
            if (pipelineBuildRecord.isEmpty()) {
                return null
            }
            val pipelineInfo = pipelineBuildRecord[0]
            watcher.start("s_r_favor")
            val favorPipelines = pipelineGroupService.getFavorByPipeline(userId, pipeline)
            val buildStatus = pipelineInfo["LATEST_STATUS"] as Int?

            val finishCount = pipelineInfo["FINISH_COUNT"] as Int? ?: 0
            val runningCount = pipelineInfo["RUNNING_COUNT"] as Int? ?: 0
            return PipelineStatus(
                taskCount = pipelineInfo["TASK_COUNT"] as Int,
                buildCount = finishCount + runningCount + 0L,
                canManualStartup = pipelineInfo["MANUAL_STARTUP"] as Int == 1,
                currentTimestamp = System.currentTimeMillis(),
                hasCollect = favorPipelines?.isNotEmpty() ?: false,
                latestBuildEndTime = (pipelineInfo["LATEST_END_TIME"] as LocalDateTime?)?.timestampmilli() ?: 0,
                latestBuildEstimatedExecutionSeconds = 1L,
                latestBuildId = pipelineInfo["LATEST_BUILD_ID"] as String,
                latestBuildNum = pipelineInfo["BUILD_NUM"] as Int,
                latestBuildStartTime = (pipelineInfo["LATEST_START_TIME"] as LocalDateTime?)?.timestampmilli() ?: 0,
                latestBuildStatus = if (buildStatus != null) {
                    if (buildStatus == BuildStatus.QUALITY_CHECK_FAIL.ordinal) {
                        BuildStatus.FAILED
                    } else {
                        BuildStatus.values()[buildStatus]
                    }
                } else {
                    null
                },
                latestBuildTaskName = pipelineInfo["LATEST_TASK_NAME"] as String?,
                lock = checkLock(pipelineInfo["RUN_LOCK_TYPE"] as Int?),
                runningBuildCount = pipelineInfo["RUNNING_COUNT"] as Int? ?: 0
            )
        } finally {
            LogUtils.printCostTimeWE(watcher, warnThreshold = 50, errorThreshold = 200)
        }
    }

    fun count(projectId: Set<String>, channelCode: ChannelCode?): Int {
        val watcher = Watcher(id = "count|${projectId.size}")
        try {
            watcher.start("s_r_c_b_p")
            return pipelineRepositoryService.countByProjectIds(projectId, channelCode)
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher)
        }
    }

    fun getPipelineByIds(pipelineIds: Set<String>, projectId: String? = null): List<SimplePipeline> {
        if (pipelineIds.isEmpty() || projectId.isNullOrBlank()) return listOf()

        val watcher = Watcher(id = "getPipelineByIds|$projectId|${pipelineIds.size}")
        try {
            watcher.start("s_r_list_b_ps")
            val pipelines = pipelineInfoDao.listInfoByPipelineIds(dslContext = dslContext, projectId = projectId, pipelineIds = pipelineIds)
            watcher.start("listTemplate")
            val templatePipelineIds = templatePipelineDao.listByPipelines(dslContext, pipelineIds).map { it.pipelineId } // TODO: 须将是否模板转为PIPELINE基本属性
            watcher.stop()
            return pipelines.map {
                SimplePipeline(
                    projectId = it.projectId,
                    pipelineId = it.pipelineId,
                    pipelineName = it.pipelineName,
                    pipelineDesc = it.pipelineDesc,
                    taskCount = it.taskCount,
                    isDelete = it.delete,
                    instanceFromTemplate = templatePipelineIds.contains(it.pipelineId)
                )
            }
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher)
        }
    }

    fun getPipelineNameByIds(
        projectId: String,
        pipelineIds: Set<String>,
        filterDelete: Boolean = true
    ): Map<String, String> {

        if (pipelineIds.isEmpty()) return mapOf()
        if (projectId.isBlank()) return mapOf()

        val watcher = Watcher(id = "getPipelineNameByIds|$projectId|${pipelineIds.size}")
        try {
            watcher.start("s_r_list_b_ps")
            return pipelineRepositoryService.listPipelineNameByIds(projectId = projectId, pipelineIds = pipelineIds, filterDelete = filterDelete)
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher, warnThreshold = 500)
        }
    }

    fun getBuildNoByByPair(buildIds: Set<String>): Map<String, String> {
        if (buildIds.isEmpty()) return mapOf()

        val watcher = Watcher(id = "getBuildNoByByPair|${buildIds.size}")
        try {
            watcher.start("s_r_bs")
            return pipelineRuntimeService.getBuildNoByByPair(buildIds)
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher, warnThreshold = 500)
        }
    }

    fun buildPipelines(
        pipelineBuildSummary: Result<out Record>,
        favorPipelines: List<String>,
        authPipelines: List<String>,
        excludePipelineId: String? = null
    ): MutableList<Pipeline> {

        val pipelines = mutableListOf<Pipeline>()
        val currentTimestamp = System.currentTimeMillis()
        val latestBuildEstimatedExecutionSeconds = 1L
        val pipelineIds = mutableSetOf<String>()
        pipelineBuildSummary.forEach {
            val pipelineId = it["PIPELINE_ID"] as String
            if (excludePipelineId != null && excludePipelineId == pipelineId) {
                return@forEach // 跳过这个
            }
            pipelineIds.add(pipelineId)
        }
        val pipelineRecords = templatePipelineDao.listByPipelines(dslContext, pipelineIds)
        val pipelineTemplateMap = mutableMapOf<String, String>()
        pipelineRecords.forEach {
            pipelineTemplateMap[it.pipelineId] = it.templateId
        }
        val pipelineGroupLabel = pipelineGroupService.getPipelinesGroupLabel(pipelineIds.toList())
        pipelineBuildSummary.forEach {
            val pipelineId = it["PIPELINE_ID"] as String
            if (excludePipelineId != null && excludePipelineId == pipelineId) {
                return@forEach // 跳过这个
            }
            val projectId = it["PROJECT_ID"] as String
            val pipelineName = it["PIPELINE_NAME"] as String
            val canManualStartup = it["MANUAL_STARTUP"] as Int == 1
            val buildNum = it["BUILD_NUM"] as Int
            val version = it["VERSION"] as Int
            val taskCount = it["TASK_COUNT"] as Int
            val creator = it["CREATOR"] as String
            val createTime = (it["CREATE_TIME"] as LocalDateTime?)?.timestampmilli() ?: 0
            val updateTime = (it["UPDATE_TIME"] as LocalDateTime?)?.timestampmilli() ?: 0

            val pipelineDesc = it["DESC"] as String?
            val runLockType = it["RUN_LOCK_TYPE"] as Int?

            val finishCount = it["FINISH_COUNT"] as Int? ?: 0
            val runningCount = it["RUNNING_COUNT"] as Int? ?: 0
            val buildId = it["LATEST_BUILD_ID"] as String?
            val startTime = (it["LATEST_START_TIME"] as LocalDateTime?)?.timestampmilli() ?: 0
            val endTime = (it["LATEST_END_TIME"] as LocalDateTime?)?.timestampmilli() ?: 0
            val starter = it["LATEST_START_USER"] as String? ?: ""
            val taskName = it["LATEST_TASK_NAME"] as String?
            val buildStatus = it["LATEST_STATUS"] as Int?
            val latestBuildStatus =
                if (buildStatus != null) {
                    if (buildStatus == BuildStatus.QUALITY_CHECK_FAIL.ordinal) {
                        BuildStatus.FAILED
                    } else {
                        BuildStatus.values()[buildStatus]
                    }
                } else {
                    null
                }
            val buildCount = finishCount + runningCount + 0L
            pipelines.add(
                Pipeline(
                    projectId = projectId,
                    pipelineId = pipelineId,
                    pipelineName = pipelineName,
                    pipelineDesc = pipelineDesc,
                    taskCount = taskCount,
                    buildCount = buildCount,
                    lock = checkLock(runLockType),
                    canManualStartup = canManualStartup,
                    latestBuildStartTime = startTime,
                    latestBuildEndTime = endTime,
                    latestBuildStatus = latestBuildStatus,
                    latestBuildNum = buildNum,
                    latestBuildTaskName = taskName,
                    latestBuildEstimatedExecutionSeconds = latestBuildEstimatedExecutionSeconds,
                    latestBuildId = buildId,
                    deploymentTime = updateTime,
                    createTime = createTime,
                    pipelineVersion = version,
                    currentTimestamp = currentTimestamp,
                    runningBuildCount = runningCount,
                    hasPermission = authPipelines.contains(pipelineId),
                    hasCollect = favorPipelines.contains(pipelineId),
                    latestBuildUserId = starter,
                    instanceFromTemplate = pipelineTemplateMap[pipelineId] != null,
                    creator = creator,
                    groupLabel = pipelineGroupLabel[pipelineId]
                )
            )
        }

        return pipelines
    }

    private fun checkLock(runLockType: Int?): Boolean {
        return if (runLockType == null) {
            false
        } else PipelineRunLockType.valueOf(runLockType) == PipelineRunLockType.LOCK
    }

    fun listPermissionPipelineName(projectId: String, userId: String): List<Map<String, String>> {
        val pipelines = pipelinePermissionService.getResourceByPermission(
            userId = userId, projectId = projectId, permission = AuthPermission.EXECUTE
        )

        val pipelineIds = pipelines.toSet()
        val newPipelineNameList = pipelineRepositoryService.listPipelineNameByIds(projectId, pipelineIds)
        val list = mutableListOf<Map<String, String>>()
        newPipelineNameList.forEach {
            list.add(mapOf(Pair("pipelineId", it.key), Pair("pipelineName", it.value)))
        }

        return list
    }

    fun getAllBuildNo(projectId: String, pipelineId: String): List<Map<String, String>> {
        val watcher = Watcher(id = "getAllBuildNo|$pipelineId")
        try {
            watcher.start("s_r_all_bn")
            val newBuildNums = pipelineRuntimeService.getAllBuildNum(projectId, pipelineId)
            watcher.stop()
            val result = mutableListOf<Map<String, String>>()
            newBuildNums.forEach {
                result.add(mapOf(Pair("key", it.toString())))
            }
            return result
        } finally {
            LogUtils.printCostTimeWE(watcher, warnThreshold = 999)
        }
    }

    // 获取整条流水线的所有运行状态
    fun getPipelineAllStatus(userId: String, projectId: String, pipeline: String): List<Pipeline>? {
        val pipelines = setOf(pipeline)
        val pipelineList = getPipelineStatus(userId, projectId, pipelines)
        return if (pipelineList.isEmpty()) {
            null
        } else {
            return pipelineList
        }
    }

    // 旧接口
    fun getPipelineIdAndProjectIdByBuildId(buildId: String): Pair<String, String> {
        val buildInfo = pipelineRuntimeService.getBuildInfo(buildId)
            ?: throw ErrorCodeException(
                statusCode = Response.Status.NOT_FOUND.statusCode,
                errorCode = ProcessMessageCode.ERROR_NO_BUILD_EXISTS_BY_ID,
                defaultMessage = "构建任务${buildId}不存在",
                params = arrayOf(buildId)
            )
        return Pair(buildInfo.pipelineId, buildInfo.projectId)
    }

    /**
     * 还原已经删除的流水线
     */
    fun restorePipeline(
        userId: String,
        projectId: String,
        pipelineId: String,
        channelCode: ChannelCode
    ) {
        val watcher = Watcher(id = "restorePipeline|$pipelineId|$userId")
        try {
            watcher.start("restorePipeline")
            val model = pipelineRepositoryService.restorePipeline(
                projectId = projectId, pipelineId = pipelineId, userId = userId,
                channelCode = channelCode, days = deletedPipelineStoreDays.toLong()
            )
            watcher.start("createResource")
            pipelinePermissionService.createResource(userId = userId, projectId = projectId, pipelineId = pipelineId, pipelineName = model.name)
        } finally {
            watcher.stop()
            LogUtils.printCostTimeWE(watcher)
        }
    }

    fun listDeletePipelineIdByProject(
        userId: String,
        projectId: String,
        page: Int?,
        pageSize: Int?,
        sortType: PipelineSortType?,
        channelCode: ChannelCode
    ): PipelineViewPipelinePage<PipelineInfo> {
        val pageNotNull = page ?: 0
        val pageSizeNotNull = pageSize ?: -1
        var slqLimit: SQLLimit? = null
        if (pageSizeNotNull != -1) slqLimit = PageUtil.convertPageSizeToSQLLimit(pageNotNull, pageSizeNotNull)

        val offset = slqLimit?.offset ?: 0
        val limit = slqLimit?.limit ?: -1
        // 数据量不多，直接全拉
        val pipelines =
            pipelineRepositoryService.listDeletePipelineIdByProject(projectId, deletedPipelineStoreDays.toLong())
        val list: List<PipelineInfo> = when {
            offset >= pipelines.size -> emptyList()
            limit < 0 -> pipelines.subList(offset, pipelines.size)
            else -> {
                val toIndex = if (pipelines.size <= (offset + limit)) pipelines.size else offset + limit
                pipelines.subList(offset, toIndex)
            }
        }
        return PipelineViewPipelinePage(
            page = pageNotNull,
            pageSize = pageSizeNotNull,
            count = pipelines.size + 0L,
            records = list
        )
    }

    fun listPermissionPipelineCount(
        userId: String,
        projectId: String,
        channelCode: ChannelCode = ChannelCode.BS,
        checkPermission: Boolean = true
    ): Int {
        val watcher = Watcher(id = "listPermissionPipelineCount|$projectId|$userId")
        try {
            watcher.start("perm_r_perm")
            val hasPermissionList = pipelinePermissionService.getResourceByPermission(
                userId = userId, projectId = projectId, permission = AuthPermission.LIST
            )
            watcher.start("s_r_c_b_id")
            return pipelineRepositoryService.countByPipelineIds(
                projectId = projectId, channelCode = channelCode, pipelineIds = hasPermissionList
            )
        } finally {
            LogUtils.printCostTimeWE(watcher = watcher)
        }
    }

    fun getFixedStages(model: Model, fixedTriggerContainer: TriggerContainer): List<Stage> {
        val stages = ArrayList<Stage>()
        val defaultTagIds = listOf(pipelineStageService.getDefaultStageTagId())
        model.stages.forEachIndexed { index, stage ->
            stage.id = stage.id ?: VMUtils.genStageId(index + 1)
            if (index == 0) {
                stages.add(stage.copy(containers = listOf(fixedTriggerContainer)))
            } else {
                model.stages.forEach {
                    if (it.name.isNullOrBlank()) it.name = it.id
                    if (it.tag == null) it.tag = defaultTagIds
                }
                stages.add(stage)
            }
        }
        return stages
    }

    fun getPipeline(projectId: String, limit: Int?, offset: Int?): PipelineViewPipelinePage<PipelineInfo> {
        logger.info("getPipeline |$projectId| $limit| $offset")
        val pipelineRecords =
            pipelineInfoDao.listPipelineInfoByProject(
                dslContext = dslContext,
                projectId = projectId,
                limit = limit!!,
                offset = offset!!
            )
        val pipelineInfos = mutableListOf<PipelineInfo>()
        pipelineRecords?.map {
            pipelineInfoDao.convert(it, null)?.let { it1 -> pipelineInfos.add(it1) }
        }
        val count = pipelineInfoDao.countByProjectIds(
            dslContext = dslContext,
            projectIds = arrayListOf(projectId),
            channelCode = null
        )
        return PipelineViewPipelinePage(
            page = limit!!,
            pageSize = offset!!,
            records = pipelineInfos,
            count = count.toLong()
        )
    }

    fun getPipelineIdByNames(
        projectId: String,
        pipelineNames: Set<String>,
        filterDelete: Boolean
    ): Map<String, String> {

        if (pipelineNames.isEmpty() || projectId.isBlank()) return mapOf()

        return pipelineRepositoryService.listPipelineIdByName(projectId, pipelineNames, filterDelete)
    }

    fun getPipelineNameVersion(pipelineId: String): Pair<String, Int> {
        val pipelineInfo = pipelineRepositoryService.getPipelineInfo(pipelineId)
        return Pair(pipelineInfo?.pipelineName ?: "", pipelineInfo?.version ?: 0)
    }

    private fun isTemplatePipeline(pipelineId: String): Boolean {
        return templatePipelineDao.isTemplatePipeline(dslContext, pipelineId)
    }
}
