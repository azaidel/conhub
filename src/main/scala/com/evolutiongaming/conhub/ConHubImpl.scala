package com.evolutiongaming.conhub

import akka.actor.ActorRefFactory
import com.evolutiongaming.concurrent.sequentially.Sequentially
import com.evolutiongaming.nel.Nel
import com.evolutiongaming.concurrent.FutureHelper._
import com.typesafe.scalalogging.LazyLogging

import scala.concurrent.duration.FiniteDuration
import scala.concurrent.{ExecutionContext, Future}


object ConHubImpl extends LazyLogging {

  type OnMsgs[M] = Nel[M] => Unit

  type Connect[Id, T, L, M] = OnMsgs[M] => (SearchEngine[Id, T, M, L], ConStates[Id, T, M], SendMsgs[Id, T, M])

  def apply[Id, T, L, K, M](
    actorFactory: ActorRefFactory,
    sequentially: Sequentially[K],
    msgOps: ConHubImpl.MsgOps[M, L, K],
    metrics: ConMetrics[Id, T, M],
    connect: Connect[Id, T, L, M])(implicit
    ec: ExecutionContext): ConHub[Id, T, M, L] = {

    new ConHub[Id, T, M, L] {

      val onMsgs: OnMsgs[M] = msgs => {
        val msgsAndCons = for {
          msg <- msgs.toList
          cons = this.cons(msg.lookup, localCall = false)
          if cons.nonEmpty
        } yield (msg, cons)

        for {
          msgsAndCons <- Nel.opt(msgsAndCons)
        } sendManyToLocal(msgsAndCons, remote = true)
      }

      val (searchEngine, conStates, sendMsgs) = connect(onMsgs)

      metrics.registerGauges(cons)

      def !(msg: M): SR = {
        logAndMeter(msg)

        def execute = {
          val lookup = msg.lookup
          val cons = this.cons(lookup)

          if (cons.nonEmpty) {
            sendMsgs.local(msg, cons, remote = false)
            val addresses = this.addresses(cons)
            if (addresses.nonEmpty) sendMsgs.remote(Nel(msg), addresses)
          }
          SendResult(cons)
        }

        msg.key match {
          case None => Future.successful(execute)
          case Some(key) => sequentially(key) { execute }
        }
      }

      def !(msgs: Nel[M]): SR = {
        for {msg <- msgs} logAndMeter(msg)

        val msgsAndCons = for {
          msg <- msgs.toList
          cons = this.cons(msg.lookup)
          if cons.nonEmpty
        } yield (msg, cons)

        val future = Nel.opt(msgsAndCons) match {
          case Some(msgsAndCons) => sendManyToLocal(msgsAndCons, remote = false)
          case None              => Future.unit
        }

        val remoteMsgs = for {
          (msg, cons) <- msgsAndCons if addresses(cons).nonEmpty
        } yield msg

        for {remoteMsgs <- Nel.opt(remoteMsgs)} sendMsgs.remote(remoteMsgs, Nil)

        future map { _ => SendResult(cons) }
      }


      def update(id: Id, version: Version, con: T, send: Conn.Send[M]): Result = {
        conStates.update(id, Conn.Local(con, send, version))
      }

      def disconnect(id: Id, version: Version, reconnectTimeout: FiniteDuration): Result = {
        conStates.disconnect(id, version, reconnectTimeout)
      }

      def remove(id: Id, version: Version): Result = {
        conStates.remove(id, version)
      }

      private def addresses(cons: Iterable[C]) = {
        cons collect { case c: C.Remote => c.address }
      }

      private def sendManyToLocal(msgsAndConnections: Nel[(M, Iterable[Conn[T, M]])], remote: Boolean) = {
        Future.traverseSequentially(msgsAndConnections.toList) { case (msg, connections) =>
          msg.key match {
            case None => Future.successful(sendMsgs.local(msg, connections, remote))
            case Some(key) => sequentially(key) { sendMsgs.local(msg, connections, remote) }
          }
        }
      }

      private implicit class MsgOpsProxy(self: M) {
        def key: Option[K] = msgOps key self
        def lookup: L = msgOps lookup self
      }

      private def logAndMeter(msg: M) = {
        logger.debug(s"<<< ${ msg.toString take 1000 }")
        metrics.onTell(msg)
      }
    }
  }


  trait MsgOps[T, L, K] {
    def lookup(x: T): L
    def key(x: T): Option[K]
  }
}
