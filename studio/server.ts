import express from "express";
import path from "path";
import { execSync } from "child_process";
import { createServer as createViteServer } from "vite";
import { GoogleGenAI } from "@google/genai";

const app = express();
const PORT = 3000;

app.use(express.json());

// Initialize Google GenAI if API key is present
const aiApiKey = process.env.GEMINI_API_KEY;
const ai = aiApiKey ? new GoogleGenAI({ apiKey: aiApiKey }) : null;

// GitHub configuration
const getGhToken = () => process.env.GH_TOKEN || process.env.GITHUB_TOKEN || "";
const DEFAULT_REPO = "GruzdevD/EDT-MCP";

// Check GitHub token and status
app.get("/api/github/status", async (req, res) => {
  const token = getGhToken();
  if (!token) {
    return res.json({
      configured: false,
      message: "Секрет GH_TOKEN не задан в переменных окружения. Добавьте его в Secrets панели настроек AI Studio."
    });
  }

  try {
    const userRes = await fetch("https://api.github.com/user", {
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/vnd.github+json",
        "User-Agent": "EDT-MCP-Studio-Releaser"
      }
    });

    if (!userRes.ok) {
      return res.json({
        configured: false,
        error: `Неверный GH_TOKEN (HTTP ${userRes.status})`
      });
    }

    const user = await userRes.json();
    return res.json({
      configured: true,
      login: user.login,
      name: user.name || user.login,
      avatar_url: user.avatar_url,
      defaultRepo: DEFAULT_REPO
    });
  } catch (err: any) {
    return res.json({
      configured: false,
      error: err.message || "Ошибка подключения к GitHub API"
    });
  }
});

// Trigger Git Push & GitHub Release
app.post("/api/github/release", async (req, res) => {
  const token = getGhToken();
  if (!token) {
    return res.status(400).json({
      success: false,
      error: "Секрет GH_TOKEN не настроен. Пожалуйста, откройте настройки AI Studio (Secrets) и добавьте GH_TOKEN с правами на запись репозитория (scope: repo)."
    });
  }

  const targetRepo = req.body?.repo || DEFAULT_REPO;
  const tagName = req.body?.tag || "v1.0.0";
  const releaseTitle = req.body?.title || `Release ${tagName}: EDT-MCP Dev Studio`;

  try {
    // 1. Ensure local commit exists
    execSync("git add -A", { stdio: "ignore" });
    try {
      execSync(`git commit -m "${releaseTitle}"`, { stdio: "ignore" });
    } catch {}

    try {
      execSync(`git tag -a ${tagName} -m "${releaseTitle}"`, { stdio: "ignore" });
    } catch {}

    // 2. Push to remote with token
    const remoteUrl = `https://x-access-token:${token}@github.com/${targetRepo}.git`;
    try {
      execSync("git remote remove origin", { stdio: "ignore" });
    } catch {}
    execSync(`git remote add origin ${remoteUrl}`, { stdio: "ignore" });

    let pushSuccess = false;
    let pushMessage = "";
    try {
      execSync("git push -u origin HEAD --tags --force", { stdio: "pipe" });
      pushSuccess = true;
      pushMessage = `Изменения и тег ${tagName} успешно запушены в ${targetRepo}`;
    } catch (pushErr: any) {
      pushMessage = pushErr.stderr?.toString() || pushErr.message;
      console.warn("Git push warning:", pushMessage);
    }

    // 3. Create release via GitHub REST API
    const releaseRes = await fetch(`https://api.github.com/repos/${targetRepo}/releases`, {
      method: "POST",
      headers: {
        Authorization: `Bearer ${token}`,
        Accept: "application/vnd.github+json",
        "Content-Type": "application/json",
        "User-Agent": "EDT-MCP-Studio-Releaser"
      },
      body: JSON.stringify({
        tag_name: tagName,
        name: releaseTitle,
        body: `## EDT-MCP Dev Studio & Eclipse Debugger — ${tagName}

### Состав релиза:
- **Генератор отладки Eclipse (.launch)**: Экспорт готовых XML конфигураций PDE и OSGi для 1C:EDT.
- **MCP Прокси и Инспектор**: Тестирование JSON-RPC команд и инструментов Model Context Protocol.
- **Интеграция со Specter**: Шаблоны взаимодействия с платформой 1C Specter.
- **AI Ассистент**: Эксперт по Tycho/Maven и архитектуре плагинов Eclipse.`,
        draft: false,
        prerelease: false
      })
    });

    if (!releaseRes.ok) {
      const errDetail = await releaseRes.text();
      return res.status(releaseRes.status).json({
        success: pushSuccess,
        pushMessage,
        error: `Ошибка создания релиза на GitHub (${releaseRes.status}): ${errDetail}`
      });
    }

    const releaseData = await releaseRes.json();
    return res.json({
      success: true,
      pushMessage,
      releaseUrl: releaseData.html_url,
      tagName: releaseData.tag_name,
      releaseId: releaseData.id
    });
  } catch (err: any) {
    console.error("Release error:", err);
    return res.status(500).json({
      success: false,
      error: err.message || "Непредвиденная ошибка при выпуске релиза"
    });
  }
});

// API Endpoint for AI Assistant for EDT-MCP & Eclipse Debugging
app.post("/api/ai-assistant", async (req, res) => {
  try {
    const { prompt, context } = req.body;
    if (!ai) {
      return res.status(500).json({ 
        error: "GEMINI_API_KEY не настроен. Пожалуйста, добавьте ключ в настройках секретов." 
      });
    }

    const systemInstruction = `Ты — эксперт по разработке плагинов для 1C:EDT (Enterprise Development Tools) и протокола Model Context Protocol (MCP), а также гуру отладки в Eclipse RCP / OSGi / PDE.
Помогай разработчикам настраивать окружение, решать проблемы с Maven/Tycho, настраивать Eclipse Launch configurations, работать с репозиторием GruzdevD/EDT-MCP и отлаживать плагины в реальном времени. Отвечай на русском языке, профессионально, четко и с примерами кода/конфигураций.`;

    const response = await ai.models.generateContent({
      model: 'gemini-2.5-flash',
      contents: [
        { role: 'user', parts: [{ text: `${systemInstruction}\n\nКонтекст: ${context || 'Общие вопросы по EDT-MCP и отладке в Eclipse'}\n\nВопрос пользователя: ${prompt}` }] }
      ]
    });

    res.json({ result: response.text });
  } catch (error: any) {
    console.error("AI Assistant error:", error);
    res.status(500).json({ error: error.message || "Ошибка при обращении к Gemini API" });
  }
});

// Health check endpoint
app.get("/api/health", (req, res) => {
  json: res.json({ status: "ok", service: "EDT-MCP Dev Studio Backend" });
});

async function startServer() {
  if (process.env.NODE_ENV !== "production") {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: "spa",
    });
    app.use(vite.middlewares);
  } else {
    const distPath = path.join(process.cwd(), 'dist');
    app.use(express.static(distPath));
    app.get('*', (req, res) => {
      res.sendFile(path.join(distPath, 'index.html'));
    });
  }

  app.listen(PORT, "0.0.0.0", () => {
    console.log(`Server running on http://localhost:${PORT}`);
  });
}

startServer();
