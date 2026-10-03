import express from 'express';
import { createServer as createViteServer } from 'vite';
import path from 'path';
import { fileURLToPath } from 'url';
import dotenv from 'dotenv';
import fs from 'fs';
import { GoogleGenAI } from "@google/genai";

dotenv.config();

const __filename = fileURLToPath(import.meta.url);
const __dirname = path.dirname(__filename);

async function startServer() {
  const app = express();
  app.use(express.json());

  // Initialize Gemini SDK with telemetry header
  const apiKey = process.env.GEMINI_API_KEY;
  let ai: GoogleGenAI | null = null;
  if (apiKey) {
    ai = new GoogleGenAI({
      apiKey,
      httpOptions: {
        headers: {
          'User-Agent': 'aistudio-build',
        }
      }
    });
  }

  // API Route for Gemini to power the interactive mobile simulator
  app.post('/api/gemini', async (req, res) => {
    const { prompt, systemInstruction } = req.body;
    if (!ai) {
      return res.status(500).json({ error: "Gemini API key is not configured in settings." });
    }
    try {
      const response = await ai.models.generateContent({
        model: "gemini-3.8-flash",
        contents: prompt,
        config: {
          systemInstruction: systemInstruction || "You are Q2, an on-device Android assistant."
        }
      });
      res.json({ text: response.text });
    } catch (err: any) {
      console.error("Gemini API Error in server.ts:", err);
      res.status(500).json({ error: err.message });
    }
  });

  // Project files loader API for client-side code viewer and bundle construction
  const projectFiles = [
    { path: "app/build.gradle.kts", localPath: "android-q2/app/build.gradle.kts" },
    { path: "app/src/main/AndroidManifest.xml", localPath: "android-q2/app/src/main/AndroidManifest.xml" },
    { path: "app/src/main/res/xml/accessibility_service_config.xml", localPath: "android-q2/app/src/main/res/xml/accessibility_service_config.xml" },
    { path: "app/src/main/res/layout/activity_main.xml", localPath: "android-q2/app/src/main/res/layout/activity_main.xml" },
    { path: "app/src/main/res/layout/overlay_read_aloud.xml", localPath: "android-q2/app/src/main/res/layout/overlay_read_aloud.xml" },
    { path: "app/src/main/res/values/strings.xml", localPath: "android-q2/app/src/main/res/values/strings.xml" },
    { path: "app/src/main/res/drawable/circle_button_bg.xml", localPath: "android-q2/app/src/main/res/drawable/circle_button_bg.xml" },
    { path: "app/src/main/java/com/q2/app/ProviderConfig.kt", localPath: "android-q2/app/src/main/java/com/q2/app/ProviderConfig.kt" },
    { path: "app/src/main/java/com/q2/app/ApiRepository.kt", localPath: "android-q2/app/src/main/java/com/q2/app/ApiRepository.kt" },
    { path: "app/src/main/java/com/q2/app/WhisperDownloadManager.kt", localPath: "android-q2/app/src/main/java/com/q2/app/WhisperDownloadManager.kt" },
    { path: "app/src/main/java/com/q2/app/WhisperEngine.kt", localPath: "android-q2/app/src/main/java/com/q2/app/WhisperEngine.kt" },
    { path: "app/src/main/java/com/q2/app/TtsManager.kt", localPath: "android-q2/app/src/main/java/com/q2/app/TtsManager.kt" },
    { path: "app/src/main/java/com/q2/app/Q2AccessibilityService.kt", localPath: "android-q2/app/src/main/java/com/q2/app/Q2AccessibilityService.kt" },
    { path: "app/src/main/java/com/q2/app/ProcessTextActivity.kt", localPath: "android-q2/app/src/main/java/com/q2/app/ProcessTextActivity.kt" },
    { path: "app/src/main/java/com/q2/app/MainViewModel.kt", localPath: "android-q2/app/src/main/java/com/q2/app/MainViewModel.kt" },
    { path: "app/src/main/java/com/q2/app/MainActivity.kt", localPath: "android-q2/app/src/main/java/com/q2/app/MainActivity.kt" },
    { path: "app/src/main/java/com/q2/app/ChatAdapter.kt", localPath: "android-q2/app/src/main/java/com/q2/app/ChatAdapter.kt" }
  ];

  app.get('/api/project-files', async (req, res) => {
    try {
      const filesWithContent = projectFiles.map(f => {
        const fullLocalPath = path.join(__dirname, f.localPath);
        const content = fs.readFileSync(fullLocalPath, 'utf-8');
        return {
          path: f.path,
          content
        };
      });
      res.json(filesWithContent);
    } catch (err: any) {
      res.status(500).json({ error: err.message });
    }
  });

  // Handle static assets vs Vite dev mode
  const isProd = process.env.NODE_ENV === 'production' || !fs.existsSync(path.resolve(__dirname, 'node_modules/vite'));
  if (!isProd) {
    const vite = await createViteServer({
      server: { middlewareMode: true },
      appType: 'custom',
    });
    app.use(vite.middlewares);
    
    app.use('*', async (req, res, next) => {
      const url = req.originalUrl;
      try {
        let template = fs.readFileSync(path.resolve(__dirname, 'index.html'), 'utf-8');
        template = await vite.transformIndexHtml(url, template);
        res.status(200).set({ 'Content-Type': 'text/html' }).end(template);
      } catch (e) {
        vite.ssrFixStacktrace(e as Error);
        next(e);
      }
    });
  } else {
    // Production serving static built files
    const distPath = path.join(__dirname, 'dist');
    if (fs.existsSync(distPath)) {
      app.use(express.static(distPath));
      app.get('*', (req, res) => {
        res.sendFile(path.join(distPath, 'index.html'));
      });
    } else {
      app.get('*', (req, res) => {
        res.status(404).send('Vite build folder "dist" is missing. Please build the frontend app.');
      });
    }
  }

  const port = 3000;
  app.listen(port, () => {
    console.log(`Server started on http://localhost:${port}`);
  });
}

startServer();
