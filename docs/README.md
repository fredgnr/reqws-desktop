# ReqWS 文档

第一次使用，先看[项目首页](../README.md)了解用途，再按[使用指南索引](guides/README.md)完成安装和操作。不要从技术方案或测试报告开始找按钮。

## 文档分类

| 分类 | 状态 | 说明 |
|---|---|---|
| [使用与开发指南](guides/README.md) | active | 安装 Desktop、创建工作区、使用 GoLand 插件，以及开发和维护项目。 |
| [需求与变更](changes/README.md) | active | 查某项功能为什么这样设计、实现到哪一步，以及做过哪些验证。 |
| [项目规范](standards/README.md) | active | 文档写法、索引规则、插件开发与测试要求。 |
| [历史参考](reference/README.md) | archived | 原始方案和旧原型，仅用于追溯，不代表当前界面或能力。 |

## 查找设计或验证记录

已知文件位置时，直接阅读有关章节。找不到时，先在需求索引中按功能名查找，再搜索正文：

```bash
rg -n -i --glob 'README.md' '<业务词|需求标识|模块名>' docs
rg -n -i --glob '*.md' '<业务词|需求标识|模块名>' docs
```

`active` 表示文档仍有效，不等于其中的全部计划已经完成。判断功能是否可用，要结合当前代码、版本发布说明和对应的实施记录。旧截图、旧测试报告只能说明当时的版本。

## 修改文档

新增或移动文件时，同步最近一级 `README.md` 和受影响的链接；只修一句话，不需要新建整套需求材料。具体格式见[项目文档规范](standards/documentation-standard.md)，中文表达和图文对应方式见[中文文档写作规范](standards/chinese-writing.md)。

完成后运行 `npm run docs:check`。它检查结构、链接和元数据，不替代真人阅读，也不能证明截图与当前软件一致。
