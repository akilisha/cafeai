# Setting up env

## Running ollama

Step 1: Create a Persistent Volume
It is recommended to use a named volume to ensure your downloaded models persist even if the container is removed
```bash
docker volume create ollama_data
```

Step 2: Run the Ollama Container
Use the docker run command to start the container.
CPU only:
```bash
docker run -d -v ollama_data:/root/.ollama -p 11434:11434 --name ollama ollama/ollama
```
With GPU acceleration (NVIDIA example):
```bash
docker run -d --gpus all -v ollama_data:/root/.ollama -p 11434:11434 --name ollama-gpu ollama/ollama
```

Step 3: Verify the Container is Running
You can check the status of your running container using the docker ps command.
```bash
docker ps
```

Step 4: Download a Model
Models are not included in the initial image. You need to execute commands within the running container to download the models you want to use (e.g., llama3).
```bash
docker exec -it ollama ollama pull llama3
```
You can find a list of available models on the [Ollama Library](https://ollama.com/library). 

Step 5: Run a Model or Use the API
Once the model is downloaded, you can interact with it either via the container's command line or the exposed REST API.

- Run interactively in the terminal:
```bash
docker exec -it ollama ollama run llama3 "Why is the sky blue?"
```
- Use the REST API from your host machine:
You can send a POST request to http://localhost:11434/api/generate with a JSON payload.
```bash
curl http://localhost:11434/api/generate -d '{ "model": "llama3", "prompt": "Why is the sky blue?", "stream": false }'
```

Optional: Use a Web UI
To get a chat interface similar to ChatGPT, you can run a separate container for a web UI, such as Open WebUI, and link it to your Ollama container.

1. Run the Open WebUI container:
```bash
docker run -d -p 3000:8080 --add-host=host.docker.internal:host-gateway -v open-webui-data:/app/backend/data --name open-webui --restart always ghcr.io/open-webui/open-webui:main
```
2. Access the UI by navigating to http://localhost:3000 in your web browser. The UI should automatically detect the Ollama service running on the host network.

