import asyncio
import websockets
import json
import logging

# O G Mess - Military Grade Relay Server
# Minimalist, zero-data collection, purely for packet forwarding.

logging.basicConfig(level=logging.INFO)
logger = logging.getLogger("OGRelay")

# Map of connected clients: { userId: websocket_session }
connected_clients = {}

async def relay_handler(websocket):
    user_id = None
    try:
        async for message_text in websocket:
            try:
                data = json.loads(message_text)
                sender_id = data.get("senderId")
                receiver_id = data.get("receiverId")
                msg_type = data.get("type")

                # Register user session on first message (presence heartbeat)
                if not user_id and sender_id:
                    user_id = sender_id
                    connected_clients[user_id] = websocket
                    logger.info(f"User {user_id} connected. Active fleet: {len(connected_clients)}")

                # Broadcast Logic
                if receiver_id == "broadcast":
                    targets = [ws for uid, ws in connected_clients.items() if uid != sender_id]
                    if targets:
                        await asyncio.gather(*[target.send(message_text) for target in targets])

                # Point-to-Point Routing
                elif receiver_id in connected_clients:
                    await connected_clients[receiver_id].send(message_text)

                else:
                    # Target offline - in a research environment, we just drop the packet.
                    # The app handles retransmission or arrival receipts.
                    pass

            except json.JSONDecodeError:
                logger.error("Invalid packet format received.")

    except websockets.exceptions.ConnectionClosed:
        logger.info(f"Connection closed for {user_id}")
    finally:
        if user_id in connected_clients:
            del connected_clients[user_id]
            logger.info(f"User {user_id} removed. Active fleet: {len(connected_clients)}")

async def main():
    # Run on port 8080 locally (ngrok will tunnel this)
    server = await websockets.serve(relay_handler, "0.0.0.0", 8080)
    logger.info("MASTER RELAY ACTIVE on port 8080")
    await server.wait_closed()

if __name__ == "__main__":
    asyncio.run(main())
