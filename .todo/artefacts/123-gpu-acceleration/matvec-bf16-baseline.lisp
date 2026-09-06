;;;; The CPU column for Bf16MatvecCrossover.java (.todo/490): one vec:matvec per shape, us
;;;; per call, JIT-warm, under --simd on the JVM class output -- the matrix at bfloat16
;;;; against an f32 vector (the fused decode kernel, .kb/bfloat16.md) and, beside it, the
;;;; same shape at f32 against f32 (the kernel it is an equivalence of). The shapes are
;;;; matvec-baseline.lisp's plus Qwen3.5-0.8B's own (hidden 1024, intermediate 3584, a
;;;; 248320-row head) and TinyLlama's (2048 / 5632 / 32000).
;;;;
;;;;   JAR=../../../target/rontolisp-0.1.0-SNAPSHOT-exec.jar
;;;;   java -jar $JAR matvec-bf16-baseline.lisp -o MvB.class --simd
;;;;   java --add-modules jdk.incubator.vector -Xmx8g MvB            # Graal, this box's default
;;;;   java --add-modules jdk.incubator.vector -Xmx8g -XX:-UseJVMCICompiler MvB   # C2

(defun fill-matrix (w rows cols)
  ;; A cheap, exact-at-bf16 fill: the patterns do not matter to a bandwidth measurement,
  ;; only that the rows are not all zero.
  (dotimes (i rows)
    (dotimes (j cols)
      (setf (aref w i j) (* 0.125 (- (mod (+ (* i 7) j) 16) 8)))))
  w)

(defun bench (rows cols type)
  (let* ((w
          (fill-matrix
           (make-array (list rows cols) :element-type type :initial-element 0.0)
           rows cols))
         (x (linalg:cos (linalg:arange 0 cols :element-type 'single-float)))
         (n (* rows cols))
         (warm (if (> n 1000000) 30 300))
         (reps
          (if (> n 20000000)
              10
              (if (> n 1000000) 30 (if (> n 200000) 200 2000))))
         (best 1e30))
    (dotimes (i warm) (vec:matvec w x))
    (dotimes (round 3)
      (let ((t0 (get-internal-real-time)))
        (dotimes (i reps) (vec:matvec w x))
        (let ((us (/ (* 1000.0 (- (get-internal-real-time) t0)) reps)))
          (when (< us best) (setq best us)))))
    (format t "~a x ~a ~a: ~,1f us/call~%" rows cols type best)))

(dolist (shape
         '((256 256) (288 288) (384 384) (512 512) (768 288) (288 768) (768 768)
           (1024 1024) (2048 1024) (1024 2048) (3584 1024) (1024 3584)
           (6144 1024) (5632 2048) (2048 5632) (2048 2048) (4096 4096)
           (32000 288) (32000 2048) (248320 1024)))
  (dolist (type '(bfloat16 single-float))
    (bench (first shape) (second shape) type)))
