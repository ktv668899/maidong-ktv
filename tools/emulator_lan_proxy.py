"""Expose an adb-forwarded emulator port to the physical LAN for QR testing."""

import argparse
import socket
import threading


def relay(source: socket.socket, target: socket.socket) -> None:
    try:
        while data := source.recv(65536):
            target.sendall(data)
    except OSError:
        pass
    finally:
        try:
            target.shutdown(socket.SHUT_WR)
        except OSError:
            pass


def handle(client: socket.socket, target_host: str, target_port: int) -> None:
    try:
        target = socket.create_connection((target_host, target_port), timeout=5)
        threading.Thread(target=relay, args=(client, target), daemon=True).start()
        relay(target, client)
    except OSError:
        client.close()


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--listen-host", default="0.0.0.0")
    parser.add_argument("--listen-port", type=int, default=8765)
    parser.add_argument("--target-host", default="127.0.0.1")
    parser.add_argument("--target-port", type=int, default=18765)
    args = parser.parse_args()

    server = socket.socket()
    server.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    server.bind((args.listen_host, args.listen_port))
    server.listen(64)
    while True:
        client, _ = server.accept()
        threading.Thread(
            target=handle,
            args=(client, args.target_host, args.target_port),
            daemon=True,
        ).start()


if __name__ == "__main__":
    main()
