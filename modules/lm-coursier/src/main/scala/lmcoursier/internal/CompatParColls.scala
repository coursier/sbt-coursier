package lmcoursier.internal

// This is required for jscala 2.12/2.13 compatibility for parallel collections
// (see https://github.com/scala/scala-parallel-collections/issues/22)
private[internal] object CompatParColls {
  val Converters = {
    import Compat._

    {
      import scala.collection.parallel._

      CollectionConverters
    }
  }

  object Compat {
    object CollectionConverters
  }
}

