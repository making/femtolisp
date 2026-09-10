;; Exact-integer operands at both widths, so a run WITH a library must print the same
;; bits as a run without one: vec:matvec, vec:matvec-into, and linalg:dot in its three
;; shapes, at sizes on both sides of every threshold. A probe, not project code.
(defvar *seed* 1)
(defun next-int ()
  (setf *seed* (mod (+ (* *seed* 1103515245) 12345) 2147483648))
  (- (mod (floor *seed* 65536) 5) 2))

(defun fill-vec (a seed)
  (setf *seed* seed)
  (dotimes (i (length a)) (setf (aref a i) (next-int)))
  a)

(defun fill-mat (a seed)
  (setf *seed* seed)
  (dotimes (i (array-dimension a 0))
    (dotimes (j (array-dimension a 1)) (setf (aref a i j) (next-int))))
  a)

(defun check (n width)
  (let* ((a (fill-mat (make-array (list n n) :element-type width) (+ n 1)))
         (b (fill-mat (make-array (list n n) :element-type width) (+ n 7)))
         (v (fill-vec (make-array n :element-type width) (+ n 3)))
         (y (make-array n :element-type width))
         (mv (vec:matvec a v))
         (mm (linalg:dot a b))
         (mv2 (linalg:dot a v))
         (vm (linalg:dot v a)))
    (vec:matvec-into y a v)
    (format t "~a ~a matvec ~a into ~a dot-mm ~a dot-mv ~a dot-vm ~a~%" n width
            (linalg:sum mv) (linalg:sum y) (linalg:sum mm) (linalg:sum mv2) (linalg:sum vm))))

(dolist (n (read-from-string (or (uiop:getenv "SIZES") "(3 4 8 9 33)")))
  (check n 'double-float)
  (check n 'single-float))
