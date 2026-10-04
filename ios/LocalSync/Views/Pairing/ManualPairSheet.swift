import SwiftUI

public struct ManualPairSheet: View {
    @ObservedObject public var viewModel: MainViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var ipAddress: String = ""
    @State private var port: String = "8080"
    @State private var token: String = ""
    @State private var pcName: String = ""

    @State private var isPairing: Bool = false
    @State private var errorMessage: String?

    public init(viewModel: MainViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        NavigationView {
            Form {
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

                if let error = errorMessage {
                    Section {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.red)
                    }
                }

                Section {
                    Button(action: startPairing) {
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
        }
    }

    private func startPairing() {
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
