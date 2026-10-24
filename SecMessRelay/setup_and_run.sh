#!/bin/bash

# O G System - Automated Connection Node Setup
# Designed for research PCs and secondary servers.

echo "💎 Initializing O G Mess Relay Infrastructure..."

# 1. Create Virtual Environment
if [ ! -d "venv" ]; then
    echo "📦 Creating Python Virtual Environment..."
    python3 -m venv venv
fi

# 2. Activate and Install Dependencies
source venv/bin/activate
pip install --upgrade pip
pip install websockets

# 3. Start Relay Server in Background
echo "🚀 Starting Master Relay Server on port 8080..."
python3 relay_server.py &
SERVER_PID=$!

# 4. Wait for Server to stabilize
sleep 2

# 5. Check for ngrok and Sync with GitHub Phonebook
if command -v ngrok &> /dev/null; then
    echo "📡 Detected ngrok. Attempting to tunnel port 8080..."

    # Try to get existing tunnel URL (if ngrok is already running)
    NEW_URL=$(curl -s http://localhost:4040/api/tunnels | python3 -c "import sys, json; print(json.load(sys.stdin)['tunnels'][0]['public_url'])" 2>/dev/null)

    if [ -z "$NEW_URL" ]; then
        echo "⚠️ ngrok tunnel not found. Please run 'ngrok http 8080' in a separate terminal."
        echo "Then run this script again to sync the fleet."
    else
        echo "🔗 New Relay Path: $NEW_URL"
        echo "📲 Syncing GitHub Phonebook (relay_config.txt)..."

        # Update relay_config.txt in the main project root
        echo "$NEW_URL" > ../relay_config.txt

        # Git Push to Private Repo
        cd ..
        git add relay_config.txt
        git commit -m "MASTER Jump: New Connection Node Active"
        git push origin main
        cd SecMessRelay

        echo "✅ FLEET SYNCED! All 100 devices are jumping to this PC."
    fi
else
    echo "❌ ngrok not found. Please install it to bridge this node to the internet."
fi

echo "---"
echo "Relay Server is running (PID: $SERVER_PID)."
echo "Keep this terminal open to maintain the connection."
wait $SERVER_PID
