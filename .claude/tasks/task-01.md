## 待完成

需求：构建一个android app。功能如下：

* 支持导入本地epub和mobi格式的文本，可阅读。
* 支持语音自动朗读。
* UI效果参考：
  * /Users/luojuncheng/lwl/AndroidStudioProjects/lwl_code/BookReader/01.jpg
  * /Users/luojuncheng/lwl/AndroidStudioProjects/lwl_code/BookReader/02.jpg
  * /Users/luojuncheng/lwl/AndroidStudioProjects/lwl_code/BookReader/03.jpg
  * /Users/luojuncheng/lwl/AndroidStudioProjects/lwl_code/BookReader/04.jpg



### 子任务拆分(执行顺序见依赖)

| 任务 | 说明 | 范围 | 优先级 |
|---|---|---|---|
| task-02 | 工程初始化 + 底部 5 Tab 导航骨架 | 本期核心 | P0 |
| task-03 | 书架页 + Room 本地数据库(图 02) | 本期核心 | P0 |
| task-04 | 本地导入 + EPUB 解析(图 01) | 本期核心 | P0 |
| task-05 | EPUB 阅读页 + 目录翻页(图 04) | 本期核心 | P0 |
| task-06 | MOBI 格式支持(风险最高) | 本期核心 | P1 |
| task-07 | 语音自动朗读 TTS | 本期核心 | P0 |
| task-08 | 阅读中首页(图 03) | 后续 | P1 |
| task-09 | 阅读设置 / 书签 / 笔记(图 04) | 后续 | P1 |
| task-10 | 云端 / 会员 / 账号占位页 | 后续 | P2 |

依赖链:02 → 03 → 04 → 05 →(06 / 07 / 08 / 09 并行);10 仅依赖 02。

## 已完成



