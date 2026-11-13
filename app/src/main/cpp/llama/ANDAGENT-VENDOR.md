llama.cpp source snapshot: https://github.com/ggml-org/llama.cpp commit 5676dfb.
Only the CMake, include, src, ggml, cmake, vendor, and license trees are retained.
The Android build disables all tools, examples, tests, server, GPU backends,
OpenMP, and KleidiAI. The app links static llama/ggml objects into llama_jni.so.
The GGML CPU backend includes its quantized matrix kernels. GGML_CUDA_FORCE_MMQ
is CUDA only and has no effect on this CPU build.
