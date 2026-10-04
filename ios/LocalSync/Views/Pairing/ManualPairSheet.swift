import SwiftUI

public struct ManualPairSheet: View {
    @ObservedObject public var viewModel: MainViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var pairingMode: Int = 0 // 0 = Paste JSON, 1 = Manual Input

    // JSON paste state
    @State private var jsonInput: String = ""
    @State private var parsedPayload: QrPayload?

    // Manual input state
    @State private var ipAddress: String = ""
    @State private var port: String = "8080"
    @State private var token: String = ""
    @State private var pcName: String = ""

    @State private var isPairing: Bool = false
    @State private var errorMessage: String?
    @State private var pasteStatusMessage: String?

    public init(viewModel: MainViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationView {
            Form {
                // Segmented picker
                Section {
                    Picker("Pairing Method", selection: $pairingMode) {
                        Text("Paste JSON").tag(0)
                        Text("Manual Input").tag(1)
                    }
                    .pickerStyle(SegmentedPickerStyle())
                }

                if pairingMode == 0 {
                    // MARK: - Paste JSON Mode
                    Section(header: Text("Desktop Pairing Config")) {
                        Button(action: pasteFromClipboard) {
                            HStack {
                                Image(systemName: "doc.on.clipboard")
                                    .font(.headline)
                                Text("Paste from Clipboard")
                                    .fontWeight(.semibold)
                                Spacer()
                            }
                            .foregroundColor(.blue)
                        }

                        if let status = pasteStatusMessage {
                            HStack(spacing: 6) {
                                Image(systemName: "checkmark.circle.fill")
                                    .foregroundColor(.green)
                                Text(status)
                                    .font(.caption)
                                    .foregroundColor(.green)
                            }
                        }

                        VStack(alignment: .leading, spacing: 6) {
                            Text("Or paste JSON text directly:")
                                .font(.caption)
                                .foregroundColor(.secondary)

                            TextEditor(text: $jsonInput)
                                .frame(height: 100)
                                .font(.system(.caption, design: .monospaced))
                                .overlay(
                                    RoundedRectangle(cornerRadius: 8)
                                        .stroke(Color.secondary.opacity(0.2), lineWidth: 1)
                                )
                                .onChange(of: jsonInput) { newValue in
                                    parseJson(newValue)
                                }
                        }
                    }

                    if let payload = parsedPayload {
                        Section(header: Text("Detected Server Details")) {
                            HStack {
                                Text("PC Name")
                                Spacer()
                                Text(payload.pcName).foregroundColor(.secondary)
                            }
                            HStack {
                                Text("LAN IP")
                                Spacer()
                                Text(payload.localIp).foregroundColor(.secondary)
                            }
                            HStack {
                                Text("Port")
                                Spacer()
                                Text("\(payload.port)").foregroundColor(.secondary)
                            }
                            if let ips = payload.ips, ips.count > 1 {
                                HStack {
                                    Text("All IPs")
                                    Spacer()
                                    Text(ips.joined(separator: ", ")).foregroundColor(.secondary).lineLimit(1)
                                }
                            }
                        }

                        Section {
                            Button(action: { pairWithParsedPayload(payload) }) {
                                HStack {
                                    Spacer()
                                    if isPairing {
                                        ProgressView()
                                            .padding(.trailing, 6)
                                    }
                                    Text("Pair with \(payload.pcName)")
                                        .fontWeight(.bold)
                                    Spacer()
                                }
                            }
                            .disabled(isPairing)
                        }
                    }
                } else {
                    // MARK: - Manual Input Mode
                    Section(header: Text("Server Connection")) {
                        TextField("PC IP Address (e.g. 100.x.y.z)", text: $ipAddress)
                            .keyboardType(.numbersAndPunctuation)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)

                        TextField("Server Port", text: $port)
                            .keyboardType(.numberPad)

                        TextField("Pairing Token", text: $token)
                            .autocapitalization(.none)
                            .disableAutocorrection(true)

                        TextField("PC Name (Optional)", text: $pcName)
                    }

                    Section {
                        Button(action: startManualPairing) {
                            HStack {
                                Spacer()
                                if isPairing {
                                    ProgressView()
                                        .padding(.trailing, 6)
                                }
                                Text("Pair with PC")
                                    .fontWeight(.bold)
                                Spacer()
                            }
                        }
                        .disabled(ipAddress.trimmingCharacters(in: .whitespaces).isEmpty || token.trimmingCharacters(in: .whitespaces).isEmpty || isPairing)
                    }
                }

                if let error = errorMessage {
                    Section {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.red)
                    }
                }
            }
            .navigationTitle("Pair Manually")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        dismiss()
                    }
                    .disabled(isPairing)
                }
            }
            .onAppear {
                // Check if clipboard contains JSON on open
                checkClipboardForJson()
            }
        }
    }

    private func checkClipboardForJson() {
        if let clip = UIPasteboard.general.string {
            parseJson(clip)
        }
    }

    private func pasteFromClipboard() {
        guard let clip = UIPasteboard.general.string, !clip.isEmpty else {
            errorMessage = "Clipboard is empty"
            return
        }
        jsonInput = clip
        parseJson(clip)
    }

    private func parseJson(_ text: String) {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard trimmed.hasPrefix("{") && trimmed.hasSuffix("}"),
              let data = trimmed.data(using: .utf8) else {
            parsedPayload = nil
            return
        }

        do {
            let payload = try JSONDecoder().decode(QrPayload.self, from: data)
            self.parsedPayload = payload
            self.pasteStatusMessage = "Detected \(payload.pcName) (\(payload.localIp):\(payload.port))"
            self.errorMessage = nil

            // Also auto-populate manual fields
            self.ipAddress = payload.localIp
            self.port = "\(payload.port)"
            self.token = payload.token
            self.pcName = payload.pcName
        } catch {
            self.parsedPayload = nil
            self.errorMessage = "Could not parse JSON: \(error.localizedDescription)"
        }
    }

    private func pairWithParsedPayload(_ payload: QrPayload) {
        isPairing = true
        errorMessage = nil

        Task {
            let result = await viewModel.pairWithPayload(payload)
            isPairing = false
            switch result {
            case .success:
                dismiss()
            case .failure(let error):
                errorMessage = error.localizedDescription
            }
        }
    }

    private func startManualPairing() {
        guard let portInt = Int(port.trimmingCharacters(in: .whitespaces)), portInt > 0 else {
            errorMessage = "Invalid port number"
            return
        }

        isPairing = true
        errorMessage = nil

        Task {
            let result = await viewModel.pairManually(
                ip: ipAddress,
                port: portInt,
                token: token,
                pcName: pcName
            )

            isPairing = false
            switch result {
            case .success:
                dismiss()
            case .failure(let error):
                errorMessage = error.localizedDescription
            }
        }
    }
}
