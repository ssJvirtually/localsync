import SwiftUI
import AVFoundation

public struct CameraScannerView: View {
    @ObservedObject public var viewModel: MainViewModel
    @Environment(\.dismiss) private var dismiss

    @State private var isTorchOn: Bool = false
    @State private var isManualPairPresented: Bool = false
    @State private var isPairing: Bool = false
    @State private var errorMessage: String?
    @State private var isCameraAuthorized: Bool = AVCaptureDevice.authorizationStatus(for: .video) == .authorized

    public init(viewModel: MainViewModel) {
        self.viewModel = viewModel
    }

    public var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()

            if isCameraAuthorized {
                CameraPreviewController(onCodeScanned: handleScannedCode)
                    .ignoresSafeArea()

                // Scanner Overlay Mask
                VStack(spacing: 24) {
                    // Top Navigation
                    HStack {
                        Button(action: { dismiss() }) {
                            Image(systemName: "xmark")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.white)
                                .padding(12)
                                .background(Circle().fill(Color.black.opacity(0.6)))
                        }

                        Spacer()

                        Button(action: toggleTorch) {
                            Image(systemName: isTorchOn ? "flashlight.on.fill" : "flashlight.off.fill")
                                .font(.system(size: 18, weight: .bold))
                                .foregroundColor(.white)
                                .padding(12)
                                .background(Circle().fill(Color.black.opacity(0.6)))
                        }
                    }
                    .padding(.horizontal, 20)
                    .padding(.top, 40)

                    Text("Scan Pairing QR Code")
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)
                        .padding(.top, 10)

                    Text("Point camera at the QR code on your PC screen")
                        .font(.subheadline)
                        .foregroundColor(.white.opacity(0.8))

                    Spacer()

                    // Target Viewfinder Reticle
                    ZStack {
                        RoundedRectangle(cornerRadius: 24)
                            .stroke(Color.white.opacity(0.8), lineWidth: 3)
                            .frame(width: 260, height: 260)

                        if isPairing {
                            ProgressView()
                                .scaleEffect(1.5)
                                .tint(.white)
                                .frame(width: 260, height: 260)
                                .background(Color.black.opacity(0.5))
                                .cornerRadius(24)
                        }
                    }

                    Spacer()

                    if let error = errorMessage {
                        Text(error)
                            .font(.caption)
                            .foregroundColor(.red)
                            .padding(.horizontal, 24)
                            .multilineTextAlignment(.center)
                    }

                    // Manual Pair Option
                    Button(action: {
                        isManualPairPresented = true
                    }) {
                        Text("Pair Manually (Tailscale / Custom IP)")
                            .font(.subheadline)
                            .fontWeight(.medium)
                            .foregroundColor(.white)
                            .padding(.vertical, 12)
                            .padding(.horizontal, 20)
                            .background(Capsule().fill(Color.white.opacity(0.2)))
                    }
                    .padding(.bottom, 40)
                }
            } else {
                // Camera Permission Required View
                VStack(spacing: 20) {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 54))
                        .foregroundColor(.white.opacity(0.8))

                    Text("Camera Access Required")
                        .font(.title2)
                        .fontWeight(.bold)
                        .foregroundColor(.white)

                    Text("LocalSync uses your camera to scan the pairing QR code displayed on your PC.")
                        .font(.body)
                        .foregroundColor(.white.opacity(0.7))
                        .multilineTextAlignment(.center)
                        .padding(.horizontal, 32)

                    Button("Grant Camera Permission") {
                        AVCaptureDevice.requestAccess(for: .video) { granted in
                            DispatchQueue.main.async {
                                self.isCameraAuthorized = granted
                            }
                        }
                    }
                    .buttonStyle(.borderedProminent)
                    .padding(.top, 8)

                    Button("Pair Manually Instead") {
                        isManualPairPresented = true
                    }
                    .foregroundColor(.blue)
                    .padding(.top, 8)
                }
            }
        }
        .sheet(isPresented: $isManualPairPresented) {
            ManualPairSheet(viewModel: viewModel)
        }
    }

    private func handleScannedCode(_ code: String) {
        guard !isPairing else { return }

        guard let data = code.data(using: .utf8),
              let payload = try? JSONDecoder().decode(QrPayload.self, from: data) else {
            return
        }

        let generator = UIImpactFeedbackGenerator(style: .medium)
        generator.impactOccurred()

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

    private func toggleTorch() {
        guard let device = AVCaptureDevice.default(for: .video), device.hasTorch else { return }
        do {
            try device.lockForConfiguration()
            device.torchMode = isTorchOn ? .off : .on
            isTorchOn.toggle()
            device.unlockForConfiguration()
        } catch {
            print("Torch error: \(error)")
        }
    }
}

private struct CameraPreviewController: UIViewControllerRepresentable {
    let onCodeScanned: (String) -> Void

    func makeUIViewController(context: Context) -> ScannerViewController {
        let controller = ScannerViewController()
        controller.onCodeScanned = onCodeScanned
        return controller
    }

    func updateUIViewController(_ uiViewController: ScannerViewController, context: Context) {}
}

private final class ScannerViewController: UIViewController, AVCaptureMetadataOutputObjectsDelegate {
    var onCodeScanned: ((String) -> Void)?
    private var captureSession: AVCaptureSession?
    private var previewLayer: AVCaptureVideoPreviewLayer?

    override func viewDidLoad() {
        super.viewDidLoad()
        view.backgroundColor = .black
        setupCaptureSession()
    }

    override func viewDidLayoutSubviews() {
        super.viewDidLayoutSubviews()
        previewLayer?.frame = view.bounds
    }

    override func viewWillAppear(_ animated: Bool) {
        super.viewWillAppear(animated)
        if captureSession?.isRunning == false {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                self?.captureSession?.startRunning()
            }
        }
    }

    override func viewWillDisappear(_ animated: Bool) {
        super.viewWillDisappear(animated)
        if captureSession?.isRunning == true {
            captureSession?.stopRunning()
        }
    }

    private func setupCaptureSession() {
        let session = AVCaptureSession()
        guard let videoDevice = AVCaptureDevice.default(for: .video),
              let videoInput = try? AVCaptureDeviceInput(device: videoDevice) else {
            return
        }

        if session.canAddInput(videoInput) {
            session.addInput(videoInput)
        }

        let metadataOutput = AVCaptureMetadataOutput()
        if session.canAddOutput(metadataOutput) {
            session.addOutput(metadataOutput)
            metadataOutput.setMetadataObjectsDelegate(self, queue: DispatchQueue.main)
            metadataOutput.metadataObjectTypes = [.qr]
        }

        let preview = AVCaptureVideoPreviewLayer(session: session)
        preview.videoGravity = .resizeAspectFill
        view.layer.addSublayer(preview)

        self.previewLayer = preview
        self.captureSession = session

        DispatchQueue.global(qos: .userInitiated).async {
            session.startRunning()
        }
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput metadataObjects: [AVMetadataObject], from connection: AVCaptureConnection) {
        guard let metadataObject = metadataObjects.first as? AVMetadataMachineReadableCodeObject,
              let stringValue = metadataObject.stringValue else {
            return
        }

        onCodeScanned?(stringValue)
    }
}
