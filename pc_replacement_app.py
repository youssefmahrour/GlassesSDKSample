import json
import socket
import threading
import tkinter as tk
from tkinter import messagebox, scrolledtext, ttk


class SmartGlassesPCApp(tk.Tk):
    def __init__(self):
        super().__init__()
        self.title("Smart Glasses PC Controller")
        self.geometry("900x600")
        self.minsize(820, 520)
        self.protocol("WM_DELETE_WINDOW", self.on_close)

        self.socket = None
        self.connected = False
        self.demo_mode = False

        self.create_ui()
        self.log_message("Desktop app started. Enter demo or a real host/port, then connect.")

    def create_ui(self):
        main = ttk.Frame(self, padding=12)
        main.pack(fill="both", expand=True)

        top = ttk.Frame(main)
        top.pack(fill="x", pady=(0, 10))

        ttk.Label(top, text="Host:").pack(side="left")
        self.host_var = tk.StringVar(value="demo")
        ttk.Entry(top, textvariable=self.host_var, width=20).pack(side="left", padx=(6, 10))

        ttk.Label(top, text="Port:").pack(side="left")
        self.port_var = tk.StringVar(value="9000")
        ttk.Entry(top, textvariable=self.port_var, width=10).pack(side="left", padx=(6, 10))

        self.connect_button = ttk.Button(top, text="Connect", command=self.toggle_connection)
        self.connect_button.pack(side="left", padx=(0, 8))

        ttk.Button(top, text="Disconnect", command=self.disconnect).pack(side="left")

        status_frame = ttk.LabelFrame(main, text="Status", padding=10)
        status_frame.pack(fill="x", pady=(0, 10))

        self.status_var = tk.StringVar(value="Disconnected")
        ttk.Label(status_frame, textvariable=self.status_var, font=("Segoe UI", 11, "bold")).pack(anchor="w")
        self.battery_var = tk.StringVar(value="Battery: --")
        ttk.Label(status_frame, textvariable=self.battery_var).pack(anchor="w", pady=(4, 0))

        body = ttk.Frame(main)
        body.pack(fill="both", expand=True)

        left = ttk.LabelFrame(body, text="Devices", padding=10)
        left.pack(side="left", fill="y", padx=(0, 10))
        self.device_list = tk.Listbox(left, height=10, width=26)
        self.device_list.pack(fill="both", expand=True)
        self.device_list.insert(0, "Demo Glasses")
        self.device_list.insert(1, "Local Host")
        self.device_list.selection_set(0)

        right = ttk.Frame(body)
        right.pack(side="right", fill="both", expand=True)

        command_frame = ttk.LabelFrame(right, text="Commands", padding=10)
        command_frame.pack(fill="x", pady=(0, 10))

        self.command_var = tk.StringVar(value="status")
        ttk.Entry(command_frame, textvariable=self.command_var).pack(side="left", fill="x", expand=True)
        ttk.Button(command_frame, text="Send", command=self.send_command).pack(side="left", padx=(8, 0))

        log_frame = ttk.LabelFrame(right, text="Log", padding=10)
        log_frame.pack(fill="both", expand=True)
        self.log_area = scrolledtext.ScrolledText(log_frame, wrap=tk.WORD, height=18)
        self.log_area.pack(fill="both", expand=True)
        self.log_area.configure(state="disabled")

    def toggle_connection(self):
        if self.connected:
            self.disconnect()
        else:
            self.connect()

    def connect(self):
        host = self.host_var.get().strip()
        port_text = self.port_var.get().strip()
        if not host or not port_text:
            messagebox.showwarning("Input", "Host and port are required.")
            return

        try:
            port = int(port_text)
        except ValueError:
            messagebox.showerror("Input", "Port must be a number.")
            return

        if host.lower() == "demo":
            self.demo_mode = True
            self.connected = True
            self.socket = None
            self.status_var.set("Connected (demo mode)")
            self.battery_var.set("Battery: 85%")
            self.log_message("Demo mode connected. You can send commands without a real device.")
            self.connect_button.config(text="Disconnect")
            return

        try:
            self.socket = socket.create_connection((host, port), timeout=3)
            self.socket.settimeout(3)
            self.connected = True
            self.demo_mode = False
            self.status_var.set(f"Connected to {host}:{port}")
            self.battery_var.set("Battery: --")
            self.log_message(f"Connected to {host}:{port}")
            self.connect_button.config(text="Disconnect")
        except OSError as exc:
            self.connected = False
            self.socket = None
            self.status_var.set("Disconnected")
            self.log_message(f"Connection failed: {exc}")
            messagebox.showerror("Connection failed", str(exc))

    def disconnect(self):
        if self.socket is not None:
            try:
                self.socket.close()
            except OSError:
                pass
        self.socket = None
        self.connected = False
        self.demo_mode = False
        self.status_var.set("Disconnected")
        self.battery_var.set("Battery: --")
        self.log_message("Disconnected.")
        self.connect_button.config(text="Connect")

    def send_command(self):
        if not self.connected:
            messagebox.showwarning("Not connected", "Connect first before sending a command.")
            return

        command = self.command_var.get().strip()
        if not command:
            messagebox.showwarning("Input", "Please enter a command.")
            return

        if self.demo_mode:
            response = self.handle_demo_command(command)
            self.log_message(f"Sent: {command}")
            self.log_message(f"Reply: {response}")
            return

        payload = json.dumps({"type": "command", "command": command}).encode("utf-8")
        try:
            self.socket.sendall(payload + b"\n")
            self.log_message(f"Sent: {command}")
            reply = self.socket.recv(4096).decode("utf-8", errors="replace").strip()
            self.log_message(f"Reply: {reply}")
        except (OSError, ConnectionResetError, TimeoutError) as exc:
            self.log_message(f"Send failed: {exc}")
            messagebox.showerror("Send failed", str(exc))

    def handle_demo_command(self, command):
        cmd = command.lower()
        if cmd in {"status", "state"}:
            return "ready"
        if cmd in {"ping", "hello"}:
            return "pong"
        if cmd in {"battery"}:
            return "85%"
        if cmd in {"help"}:
            return "Available: status, ping, battery, help"
        return f"unknown command: {command}"

    def log_message(self, message):
        self.log_area.configure(state="normal")
        self.log_area.insert(tk.END, message + "\n")
        self.log_area.see(tk.END)
        self.log_area.configure(state="disabled")

    def on_close(self):
        self.disconnect()
        self.destroy()


if __name__ == "__main__":
    app = SmartGlassesPCApp()
    app.mainloop()
