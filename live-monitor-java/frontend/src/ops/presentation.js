const actions = { import_image: '导入镜像包', images: '读取镜像列表', validate: '部署前校验', deploy: '部署应用', rollback: '回滚版本', start: '启动应用', stop: '停止应用', restart: '重启应用', probe: '采集状态', pull: '拉取镜像', logs: '读取日志', stack_deploy: '备份并发布应用栈', stack_probe: '采集应用栈', stack_service_start: '启动服务', stack_service_stop: '停止服务', stack_service_restart: '重启服务' }
const statuses = { QUEUED: '等待执行', RUNNING: '执行中', SUCCEEDED: '已完成', FAILED: '失败', INTERRUPTED: '执行中断，需核对' }
const messages = { QUEUED: '等待执行', CONNECTING: '正在连接服务器', AGENT_ACCEPTED: '服务器已接受任务', RECEIVE_ARCHIVE: '正在传输镜像包', VALIDATE_ARCHIVE: '正在校验镜像包', IMPORT_IMAGE: '正在解压并导入镜像', PULL_IMAGE: '正在准备镜像', PREPARE_SWITCH: '正在切换版本', WAIT_HEALTHY: '等待运行检查', COMPLETED: '操作完成' }
Object.assign(actions, {install_upload:'检查安装包',install_plan:'安装前检查',install_apply:'安装 Runtime OS'})
Object.assign(messages, {INSTALL_RECEIVING:'正在传输安装包',INSTALL_VERIFYING_SIGNATURE:'正在验证安装包签名和内容',INSTALL_PACKAGE_READY:'安装包校验完成',INSTALL_CHECKING_ENVIRONMENT:'正在检查主机资源和端口',INSTALL_IMPORTING_IMAGES:'正在导入应用及依赖镜像',INSTALL_STARTING_DEPENDENCIES:'正在启动依赖和应用',INSTALL_VERIFYING_HEALTH:'正在等待初始化与健康检查',INSTALL_STOPPING_FAILED_APPLICATION:'安装未完成，正在停止本次应用并保留数据'})
export const actionLabel = value => actions[value] || value
export const statusLabel = value => statuses[value] || value
export const messageLabel = value => messages[value] || value
