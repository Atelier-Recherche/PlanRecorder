# Third-party notices

## DPDFNet (Ceva-IP)

- Project: [ceva-ip/DPDFNet](https://github.com/ceva-ip/DPDFNet)
- Models: [Ceva-IP/DPDFNet on Hugging Face](https://huggingface.co/Ceva-IP/DPDFNet)
- License: Apache License 2.0
- Usage in PlanRecorder: optional live speech enhancement (`baseline.tflite`, 16 kHz) when **AI noise reduction (DPDFNet)** is enabled in settings.

## TensorFlow Lite

- Package: `org.tensorflow:tensorflow-lite`
- License: Apache License 2.0
- Usage: on-device inference for the DPDFNet baseline model.

## Bench checklist (DPDFNet live)

When validating a build with AI noise reduction enabled:

1. Record 10–30 minutes on low / mid / high-end phones (CPU %, thermals, audio xruns).
2. Compare A/B with the toggle off (same mic position / room noise).
3. Confirm mutual exclusion with **Filter for voices** (Android `NoiseSuppressor`).
4. Confirm export normalization still runs on the already-denoised file when enabled.
