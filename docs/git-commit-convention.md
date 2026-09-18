# Git提交规范
所有提交记录必须遵循约定式提交格式：
文本
<类型>(<作用域>): <主题>

作用域为可选项。支持的类型包括`feat`、`fix`、`docs`、`style`、
`refactor`、`perf`、`test`、`build`、`ci`、`chore`和`revert`。

示例：
文本
feat(admin): 为分页列表添加分页大小选择器
fix(knowledge): 删除最后一条记录后重置页面
docs: 编写生产环境部署流程文档

本地Husky钩子会拦截不符合规范的提交信息。GitHub Actions会校验
拉取请求与推送操作中的所有提交。在GitHub分支保护规则中，将`Commitlint`状态检查
设置为`master`分支的强制校验项。

提交合并至`master`分支后，语义化发布工具会自动创建Git标签与GitHub发布版本：`feat`触发次版本发布，`fix`触发补丁版本发布，`feat!`或者带有`BREAKING CHANGE:`脚注的提交触发主版本发布。
