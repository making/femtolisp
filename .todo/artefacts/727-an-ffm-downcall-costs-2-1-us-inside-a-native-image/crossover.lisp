;; Where a CBLAS call stops paying in THIS runtime: linalg:dot (n x n by n x n) and
;; vec:matvec (n x n by n) at small n, us per call, under whatever flags the run has.
;; Run it with and without --blas and diff. A probe, not project code.
(linalg:seed 7)

(defun bench (name n thunk reps)
  (funcall thunk)
  (let ((t0 (get-internal-real-time)))
    (dotimes (i reps) (funcall thunk))
    (let ((ms (- (get-internal-real-time) t0)))
      (format t "~a n=~a ~,3f us/call (~a reps)~%" name n (/ (* 1000.0 ms) reps) ms))))

(dolist (n (read-from-string (or (uiop:getenv "SIZES") "(2 4 8 16 32 64)")))
  (let* ((a (linalg:randn (list n n)))
         (b (linalg:randn (list n n)))
         (v (linalg:randn n))
         (reps-dot (max 500 (min 200000 (floor 200000000 (* n n n)))))
         (reps-mv (max 500 (min 200000 (floor 200000000 (* n n))))))
    (unless (equal (uiop:getenv "WHAT") "mv")
      (bench "linalg:dot " n (lambda () (linalg:dot a b)) reps-dot))
    (unless (equal (uiop:getenv "WHAT") "dot")
      (bench "vec:matvec " n (lambda () (vec:matvec a v)) reps-mv))))
