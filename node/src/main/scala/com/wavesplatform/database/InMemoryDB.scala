package com.wavesplatform.database

import java.util

import com.google.common.cache.{CacheBuilder, CacheLoader}
import com.google.common.primitives.UnsignedBytes
import com.typesafe.scalalogging.LazyLogging
import com.wavesplatform.database.InMemoryDB.ByteArrayHashingStrategy
import com.wavesplatform.settings.InMemorySettings
import org.eclipse.collections.api.block.HashingStrategy
import org.eclipse.collections.api.tuple.Pair
import org.eclipse.collections.impl.factory.{HashingStrategyMaps, HashingStrategySets}
import org.eclipse.collections.impl.utility.MapIterate
import org.iq80.leveldb.*

import scala.compat.java8.FunctionConverters.*

class KW private (val bs: Array[Byte]) extends Comparable[KW] {
  override def equals(obj: Any): Boolean = obj match {
    case kw: KW => (kw eq this) || util.Arrays.equals(bs, kw.bs)
    case _      => false
  }

  private lazy val _hashCode = util.Arrays.hashCode(bs)

  override def hashCode(): Int = _hashCode

  override def compareTo(o: KW): Int = UnsignedBytes.lexicographicalComparator().compare(bs, o.bs)
}

object KW {
  def apply(bs: Array[Byte]): KW = new KW(bs)
}

class TestBatch extends WriteBatch {
  val newEntries     = HashingStrategyMaps.mutable.`with`[Array[Byte], Array[Byte]](ByteArrayHashingStrategy)
  val deletedEntries = HashingStrategySets.mutable.`with`[Array[Byte]](ByteArrayHashingStrategy)

  override def put(key: Array[Byte], value: Array[Byte]): WriteBatch = {
    deletedEntries.remove(key)
    newEntries.put(key, value)
    this
  }

  override def delete(key: Array[Byte]): WriteBatch = {
    newEntries.remove(key)
    deletedEntries.add(key)
    this
  }

  override def close(): Unit = {}
}

object InMemoryDB {
  object ByteArrayHashingStrategy extends HashingStrategy[Array[Byte]] {
    override def computeHashCode(obj: Array[Byte]): Int = util.Arrays.hashCode(obj)

    override def equals(object1: Array[Byte], object2: Array[Byte]): Boolean = util.Arrays.equals(object1, object2)
  }
}

class InMemoryDB(underlying: DB, settings: InMemorySettings) extends DB with LazyLogging {
  import InMemoryDB.*

  private var estimatedSize: Long = 0L
  private var batchCount          = 0L
  private val entries             = HashingStrategyMaps.mutable.`with`[Array[Byte], Array[Byte]](ByteArrayHashingStrategy)
  private val toDelete            = HashingStrategySets.mutable.`with`[Array[Byte]](ByteArrayHashingStrategy)

  private val cc = CacheBuilder
    .newBuilder()
    .maximumWeight(settings.maxCacheWeight)
    .weigher((_: KW, value: Array[Byte]) => value.length)
    .build[KW, Array[Byte]](new CacheLoader[KW, Array[Byte]] {
      override def load(key: KW): Array[Byte] = {
        val u = underlying.get(key.bs)
        if (u == null) Array.emptyByteArray else u
      }
    })

  logger.info(s"Max batch size = ${settings.batchSize}")

  override def get(key: Array[Byte]): Array[Byte] =
    entries.getOrDefault(
      key,
      if (toDelete.contains(key)) null
      else {
        val kw = KW(key)
        val bs = cc.get(kw)
        if (bs.length == 0) null else bs
      }
    )

  override def get(key: Array[Byte], options: ReadOptions): Array[Byte] = get(key)

  override def iterator(): DBIterator = iterator(new ReadOptions)

  override def iterator(options: ReadOptions): DBIterator = underlying.iterator()

  private def putAndCountBytes(key: Array[Byte], value: Array[Byte]): Unit = {
    toDelete.remove(key)
    estimatedSize += (key.length + value.length)
    cc.invalidate(KW(key))
    Option(entries.put(key, value)).foreach { bs =>
      estimatedSize -= (key.length + bs.length)
    }
  }

  private def deleteAndCountBytes(key: Array[Byte]): Unit = {
    toDelete.add(key)
    Option(entries.remove(key)).foreach(v => estimatedSize -= (key.length + v.length))
  }

  override def put(key: Array[Byte], value: Array[Byte]): Unit = putAndCountBytes(key, value)

  override def delete(key: Array[Byte]): Unit = deleteAndCountBytes(key)

  private def flush(): Unit = {
    logger.info(s"${toDelete.size} keys to delete, ${entries.size} to add")

    val batch = underlying.createWriteBatch()

    MapIterate
      .toListOfPairs(entries)
      .sortThis((o1: Pair[Array[Byte], Array[Byte]], o2: Pair[Array[Byte], Array[Byte]]) =>
        UnsignedBytes.lexicographicalComparator().compare(o1.getOne, o2.getOne)
      )
      .forEach({ (t: Pair[Array[Byte], Array[Byte]]) => batch.put(t.getOne, t.getTwo); () }.asJava)

    toDelete.forEach({ (k: Array[Byte]) => batch.delete(k); () }.asJava)

    underlying.write(batch)

    cc.invalidateAll()
    logger.info("Finished persisting")
    entries.clear()
    toDelete.clear()
    estimatedSize = 0L
  }

  override def write(updates: WriteBatch): Unit = updates match {
    case tb: TestBatch =>
      batchCount += 1
      tb.deletedEntries.forEach((k => deleteAndCountBytes(k)).asJava)
      tb.newEntries.forEach((k, v) => putAndCountBytes(k, v))

      if (estimatedSize > settings.maxCacheWeight) {
        flush()
      }

    case _ => ???
  }

  override def createWriteBatch(): WriteBatch = new TestBatch

  override def put(key: Array[Byte], value: Array[Byte], options: WriteOptions): Snapshot = {
    put(key, value)
    getSnapshot
  }

  override def delete(key: Array[Byte], options: WriteOptions): Snapshot = {
    delete(key)
    getSnapshot
  }

  override def write(updates: WriteBatch, options: WriteOptions): Snapshot = {
    write(updates)
    getSnapshot
  }

  override def getSnapshot: Snapshot = () => ()

  override def getApproximateSizes(ranges: Range*): Array[Long] = ???

  override def getProperty(name: String): String = ""

  override def suspendCompactions(): Unit = {}

  override def resumeCompactions(): Unit = {}

  override def compactRange(begin: Array[Byte], end: Array[Byte]): Unit = {}

  override def close(): Unit = flush()
}
