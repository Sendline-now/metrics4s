package metrics4s.runtime.kyo

import kyo.{<, Abort, Async}

type KIO[A] = A < (Async & Abort[Throwable])
