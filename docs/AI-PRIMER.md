# A walk down AI street

> A plain-language primer for Java developers new to LLMs — the difference between
> a model runner and a model, open-weight vs API models, and running one locally.
> **Unfinished:** the "CafeAI workflow" section is a stub; for that, see
> `GETTING-STARTED.md` and the `cafeai-examples` module.

## About AI models

There is a subtle distinction that is often overlooked or missed or understated about AI models, which is about the tools 
that run AI models verses the AI models themselves. The two are typically shipped separately, but they can also come bundled 
in some cases.

Consider Ollama and Llama - Ollama is a software tool that runs models, while Llama is an AI model family created by Meta.
Llama comes in different versions such as Llama 3, Llama 3.1, Llama 3.2, which simply distinguished between different model
generations of the same model family.

>Think of Ollama as a _video player_ and Llama as a _movie_. You need the player to watch the movie.

Additionally, AI tools are designed for running AI models on local hardware, which even on a pretty juiced-up developer 
laptop can easily take on.

| AI Tool  |      About the tool      | 
| LM Studio: A highly popular desktop application with a clean visual user interface (UI). It features a built-in model marketplace, an OpenAI-compatible local server, and requires zero command-line usage. |
| Mochi 1 (and other local pipelines): | Specialized frameworks or wrappers designed specifically for running complex generative video and image models locally.
| AnythingLLM: | An all-in-one desktop application designed to turn local models into custom AI agents. It excels at local RAG (Retrieval-Augmented Generation), allowing you to chat with your local PDFs and documents privately.
| KoboldCPP: | A lightweight, single-file executable optimized heavily for text generation and roleplay. It is popular in the gaming and creative writing open-source communities.
| vLLM: | A high-throughput, industrial-grade model serving engine designed for software developers and enterprise pipelines rather than consumer desktops.

On the other hand, AI models are broadly split into two categories: 
- Open-Weights (which you can download and run via Ollama or LM Studio) and: 
- Closed-Source/API-driven (which run on corporate cloud servers).

1. Open-Weights Models (Run them locally)
   - Mistral & Mixtral: Developed by Mistral AI (a French startup). Highly efficient models known for excellent multilingual capabilities and strong performance-to-size ratios.
   - Gemma: Created by Google. Built on the same research and technology components used to create their flagship Gemini models.
   - Phi: Developed by Microsoft. These are "Small Language Models" (SLMs) optimized to be incredibly smart despite having small file sizes, making them perfect for lightweight laptops.
   - DeepSeek-R1 / V3: Powerful open models focused on deep reasoning, math, and code generation.Qwen: Built by Alibaba. Routinely tops open-source benchmarks for coding, mathematics, and multilingual tasks.
2. Closed-Source / Cloud APIs (Access via web browser or paid API)
   - OpenAI GPT-4o / GPT-5.6: Industry-standard models powering ChatGPT, optimized heavily for multi-modal tasks like voice, vision, and text.
   - Google Gemini 1.5 / 2.0 Pro: Cloud models featuring massive "context windows," allowing them to read entire books or hours of video at once.
   - Anthropic Claude 3.5 Sonnet: Highly praised by programmers for possessing some of the best software engineering and logical reasoning capabilities on the market.
   
## Install LLM model locally

1. Head over to [Ollama website](https://ollama.com/) and follow the instructions to install Ollama
2. Choose a model to download and install. For this example, let's consider the basic qwen2.5 or the slightly better llama3.2
3. Use the terminal to check that the Ollama is running and reachable
```bash
# using qwen2.5 model
~> curl http://localhost:11434/api/generate -d '{
  "model": "qwen2.5",
  "prompt": "Why is the sky blue?",
  "stream": false
}'

# using llama3.2 model
~> curl http://localhost:11434/api/generate -d '{
  "model": "llama3.2",
  "prompt": "Why is the sky blue?",
  "stream": false
}'
```

## CafeAI workflow 

