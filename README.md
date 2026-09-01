# GLMChatBot
A grasscutter  Genshin chatbot
也许有一天你能看到两个丘丘人在那讨论微积分
## 配置文件示例 java 17 

以下是 `plugins/GLMChatBot/plugin.json` 的示例配置：

```json
{
  "bridgeUrl": "http://localhost:5117/chat",
  "apiBase": "https://api.deepseek.com",
  "apiKey": "ABC----234567890",
  "model": "deepseek-v4-pro",
  "systemPrompt": "你是一个原神游戏内的AI助手，名叫Luna。
请用中文回复，语气友好亲切，像一个游戏内的NPC角色。
回复要简洁有趣，适合游戏内聊天窗口显示。回复不超过100字。",
  "enableTeamChat": true,
  "enablePrivateChat": true
}

