package utils

import java.time.{LocalDateTime, ZoneOffset}
import java.util.Date

/**
 * common
 *
 * @author colin
 * @version 1.0, 2020/7/21
 * @since 0.4.1
 */
object TimeUtils {
  val offsetZone8: ZoneOffset = ZoneOffset.ofHours(8)

  def toDate(localDateTime: LocalDateTime): Date = Date.from(localDateTime.toInstant(offsetZone8))
}
