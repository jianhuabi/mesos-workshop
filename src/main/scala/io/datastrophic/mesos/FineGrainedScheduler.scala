package io.datastrophic.mesos

import java.util
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

import org.apache.mesos.Protos._
import org.apache.mesos.{Scheduler, SchedulerDriver}
import org.slf4j.LoggerFactory

import scala.collection.JavaConversions._
import scala.collection.mutable

class FineGrainedScheduler(val config: Config) extends ThrottleScheduler {

   private val logger = LoggerFactory.getLogger(classOf[FineGrainedScheduler])
   private val stateLock = new ReentrantLock()

   val queriesToRun = new AtomicInteger(config.totalQueries)
   val currentTasks = new AtomicInteger(0)
   val errors = new AtomicInteger(0)

   override def statusUpdate(driver: SchedulerDriver, status: TaskStatus): Unit = {
      status.getState match {
         case TaskState.TASK_FINISHED =>
            logger.info(s"Task finished on slave ${status.getSlaveId.getValue}. Message: ${deserialize[String](status.getData.toByteArray)}")
            currentTasks.decrementAndGet()

            if(queriesToRun.get() == 0){
               logger.info(s"All queries launched, exit now.")
               System.exit(0)
            }
         case TaskState.TASK_ERROR =>
            logger.error(s"Task error on slave ${status.getSlaveId.getValue}. Exception message: ${deserialize[String](status.getData.toByteArray)}. Total " +
               s"errors: ${errors.get()}")
            currentTasks.decrementAndGet()

            if(errors.incrementAndGet() > 5){
               logger.info("Too many errors in tasks, shutting down.")
               System.exit(1)
            }

         case _ =>
            logger.info(s"${status.toString}")
      }
   }

   override def resourceOffers(driver: SchedulerDriver, offers: util.List[Offer]): Unit = {
      for(offer <- offers){
         stateLock.synchronized {
            if(queriesToRun.get() > 0) {
               if(currentTasks.get() <= config.parallelism){
                  logger.info(s"Launching task on slave ${offer.getSlaveId.getValue}")

                  val numberOfQueries = if(queriesToRun.get() < config.queriesPerTask) queriesToRun.get() else config.queriesPerTask

                  launch(driver, offer, numberOfQueries)

                  currentTasks.incrementAndGet()
                  queriesToRun.getAndSet(queriesToRun.get() - numberOfQueries)
               } else {
                  logger.info(s"Already running ${config.parallelism} tasks on cluster. Declining the offer")
                  driver.declineOffer(offer.getId)
               }
            } else {
               logger.info(s"All queries launched, waiting for tasks to complete. Declining offer.")
               driver.declineOffer(offer.getId)
            }
         }
      }
   }


}
