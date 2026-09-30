import AVFoundation
import SwiftUI
import UIKit

/// Камера для QR-кода пресета. Нашла код EQ — сразу возвращает его.
struct ScannerScreen: View {
    var found: (String) -> Void
    @Environment(\.dismiss) var dismiss
    @State var denied = false

    var body: some View {
        ZStack {
            Color.black.ignoresSafeArea()
            if denied {
                VStack(spacing: 16) {
                    Image(systemName: "camera.fill")
                        .font(.system(size: 44))
                        .foregroundStyle(Palette.grey)
                    Text(L("scan.denied"))
                        .multilineTextAlignment(.center)
                        .foregroundStyle(.white)
                    WideButton(text: L("scan.settings"), systemImage: "gear") {
                        if let url = URL(string: UIApplication.openSettingsURLString) {
                            UIApplication.shared.open(url)
                        }
                    }
                }
                .padding(32)
            } else {
                CameraView(found: found, denied: $denied)
                    .ignoresSafeArea()
                RoundedRectangle(cornerRadius: 32, style: .continuous)
                    .stroke(Color.white, lineWidth: 4)
                    .frame(width: 250, height: 250)
                VStack {
                    Spacer()
                    Text(L("scan.hint"))
                        .font(.system(size: 16, weight: .medium))
                        .padding(.horizontal, 18)
                        .padding(.vertical, 10)
                        .background(.ultraThinMaterial, in: Capsule())
                        .padding(.bottom, 40)
                }
            }
            VStack {
                HStack {
                    Spacer()
                    Button {
                        dismiss()
                    } label: {
                        Image(systemName: "xmark")
                            .font(.system(size: 17, weight: .bold))
                            .foregroundStyle(.white)
                            .frame(width: 44, height: 44)
                            .background(.ultraThinMaterial, in: Circle())
                    }
                    .padding(16)
                }
                Spacer()
            }
        }
        .preferredColorScheme(.dark)
    }
}

private struct CameraView: UIViewRepresentable {
    var found: (String) -> Void
    @Binding var denied: Bool

    func makeCoordinator() -> Coordinator {
        Coordinator(found: found)
    }

    func makeUIView(context: Context) -> PreviewView {
        let v = PreviewView()
        let c = context.coordinator
        switch AVCaptureDevice.authorizationStatus(for: .video) {
        case .authorized:
            c.start(in: v)
        case .notDetermined:
            AVCaptureDevice.requestAccess(for: .video) { ok in
                DispatchQueue.main.async {
                    if ok {
                        c.start(in: v)
                    } else {
                        denied = true
                    }
                }
            }
        default:
            DispatchQueue.main.async {
                denied = true
            }
        }
        return v
    }

    func updateUIView(_ uiView: PreviewView, context: Context) {}

    static func dismantleUIView(_ uiView: PreviewView, coordinator: Coordinator) {
        coordinator.stop()
    }

    final class PreviewView: UIView {
        override class var layerClass: AnyClass {
            AVCaptureVideoPreviewLayer.self
        }

        var preview: AVCaptureVideoPreviewLayer {
            layer as! AVCaptureVideoPreviewLayer
        }
    }

    final class Coordinator: NSObject, AVCaptureMetadataOutputObjectsDelegate {
        let found: (String) -> Void
        let session = AVCaptureSession()
        private var done = false

        init(found: @escaping (String) -> Void) {
            self.found = found
        }

        func start(in view: PreviewView) {
            guard let cam = AVCaptureDevice.default(for: .video),
                  let input = try? AVCaptureDeviceInput(device: cam),
                  session.canAddInput(input) else { return }
            session.addInput(input)
            let out = AVCaptureMetadataOutput()
            guard session.canAddOutput(out) else { return }
            session.addOutput(out)
            out.setMetadataObjectsDelegate(self, queue: .main)
            out.metadataObjectTypes = [.qr]
            view.preview.session = session
            view.preview.videoGravity = .resizeAspectFill
            let s = session
            DispatchQueue.global(qos: .userInitiated).async {
                s.startRunning()
            }
        }

        func stop() {
            let s = session
            DispatchQueue.global(qos: .userInitiated).async {
                s.stopRunning()
            }
        }

        func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
                            from connection: AVCaptureConnection) {
            guard !done else { return }
            for o in objects {
                if let code = (o as? AVMetadataMachineReadableCodeObject)?.stringValue, PresetCode.decode(code) != nil {
                    done = true
                    Haptic.success()
                    stop()
                    found(code)
                    return
                }
            }
        }
    }
}
