package mesosphere.marathon
package core.task.state

import mesosphere.marathon.stream._
import mesosphere.marathon.state._
import org.apache.mesos
import org.slf4j.LoggerFactory

import scala.annotation.tailrec

/**
  * Metadata about a task's networking information
  *
  * @param hasConfiguredIpAddress True if the associated app definition has configured an IP address
  * @param hostPorts The hostPorts as taken originally from the accepted offer
  * @param effectiveIpAddress the task's effective IP address, computed from runSpec, hostName and ipAddresses
  * @param ipAddresses all associated IP addresses, computed from mesosStatus
  */
case class NetworkInfo(
    hasConfiguredIpAddress: Boolean,
    hostPorts: Seq[Int],
    effectiveIpAddress: Option[String],
    ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress]) {

  import NetworkInfo._

  // TODO(cleanup): this should be a val, but we currently don't have the app when updating a task with a TaskStatus
  def portAssignments(app: AppDefinition): Seq[PortAssignment] =
    computePortAssignments(app, hostPorts, effectiveIpAddress,
      app.networks.hasContainerNetworking && ipAddresses.exists(ip => ip.hasIpAddress && ip.getIpAddress.nonEmpty))

  /**
    * Update the network info with the given mesos TaskStatus. This will eventually update ipAddresses and the
    * effectiveIpAddress.
    *
    * Note: Only makes sense to call this the task just became running as the reported ip addresses are not
    * expected to change during a tasks lifetime.
    */
  def update(mesosStatus: mesos.Protos.TaskStatus): NetworkInfo = {
    val newIpAddresses = resolveIpAddresses(mesosStatus)

    if (ipAddresses != newIpAddresses) {
      val newEffectiveIpAddress = if (hasConfiguredIpAddress) {
        pickFirstIpAddressFrom(newIpAddresses)
      } else {
        effectiveIpAddress
      }
      copy(ipAddresses = newIpAddresses, effectiveIpAddress = newEffectiveIpAddress)
    } else {
      // nothing has changed
      this
    }
  }

  def copyWith(
    runSpec: RunSpec,
    hostName: String,
    hostPorts: Seq[Int] = hostPorts,
    ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress] = ipAddresses
  ): NetworkInfo = NetworkInfo(runSpec, hostName, hostPorts, ipAddresses)
}

object NetworkInfo {
  val empty: NetworkInfo = new NetworkInfo(hasConfiguredIpAddress = false, hostPorts = Nil, effectiveIpAddress = None, ipAddresses = Nil)
  val log = LoggerFactory.getLogger(getClass)

  /**
    * Pick the IP address based on an ip address configuration as given in teh AppDefinition
    *
    * Only applicable if the app definition defines an IP address. PortDefinitions cannot be configured in addition,
    * and we currently expect that there is at most one IP address assigned.
    */
  private[state] def pickFirstIpAddressFrom(ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress]): Option[String] = {
    ipAddresses.headOption.map(_.getIpAddress)
  }

  def apply(
    runSpec: RunSpec,
    hostName: String,
    hostPorts: Seq[Int],
    ipAddresses: Seq[mesos.Protos.NetworkInfo.IPAddress]): NetworkInfo = {

    val hasConfiguredIpAddress: Boolean = runSpec.networks.hasContainerNetworking

    val effectiveIpAddress =
      if (hasConfiguredIpAddress) {
        if (ipAddresses.exists(ip => ip.hasIpAddress && ip.getIpAddress.nonEmpty)) {
          pickFirstIpAddressFrom(ipAddresses)
        } else {
          None
        }
      } else {
        // TODO(PODS) extract ip address from launched task
        Some(hostName)
      }

    new NetworkInfo(hasConfiguredIpAddress, hostPorts, effectiveIpAddress, ipAddresses)
  }

  private[state] def resolveIpAddresses(mesosStatus: mesos.Protos.TaskStatus): Seq[mesos.Protos.NetworkInfo.IPAddress] = {
    if (mesosStatus.hasContainerStatus && mesosStatus.getContainerStatus.getNetworkInfosCount > 0) {
      mesosStatus.getContainerStatus.getNetworkInfosList.flatMap(_.getIpAddressesList)(collection.breakOut)
    } else {
      Nil
    }
  }

  private[state] def computePortAssignments(
    app: AppDefinition,
    hostPorts: Seq[Int],
    effectiveIpAddress: Option[String],
    hasAssignedIpAddress: Boolean): Seq[PortAssignment] = {

    def fromPortMappings(container: Container): Seq[PortAssignment] = {
      import Container.PortMapping
      @tailrec
      def gen(ports: List[Int], mappings: List[PortMapping], assignments: List[PortAssignment]): List[PortAssignment] = {
        (ports, mappings) match {
          case (hostPort :: xs, PortMapping(containerPort, Some(_), _, _, portName, _) :: rs) =>
            // agent port was requested
            val assignment = PortAssignment(
              portName = portName,
              effectiveIpAddress = effectiveIpAddress,
              // very strange that an IP was not allocated for non-host networking, but make the best of it...
              effectivePort = effectiveIpAddress.fold(PortAssignment.NoPort)(_ => if (hasAssignedIpAddress) containerPort else hostPort),
              hostPort = Option(hostPort),
              containerPort = Option(containerPort)
            )
            gen(xs, rs, assignment :: assignments)
          case (_, mapping :: rs) if mapping.hostPort.isEmpty =>
            // no port was requested on the agent
            val assignment = PortAssignment(
              portName = mapping.name,
              // if there's no assigned IP and we have no host port, then this container isn't reachable
              effectiveIpAddress = if (hasAssignedIpAddress) effectiveIpAddress else None,
              // just pick containerPort; we don't have an agent port to fall back on regardless,
              // of effectiveIp or hasAssignedIpAddress
              effectivePort = if (hasAssignedIpAddress) mapping.containerPort else PortAssignment.NoPort,
              hostPort = None,
              containerPort = Some(mapping.containerPort)
            )
            gen(ports, rs, assignment :: assignments)
          case (Nil, Nil) =>
            assignments
          case _ =>
            throw new IllegalStateException(
              s"failed to align remaining allocated host ports $ports with remaining declared port mappings $mappings")
        }
      }
      gen(hostPorts.to[List], container.portMappings.to[List], Nil).reverse
    }

    def fromPortDefinitions: Seq[PortAssignment] =
      app.portDefinitions.zip(hostPorts).map {
        case (portDefinition, hostPort) =>
          PortAssignment(
            portName = portDefinition.name,
            effectiveIpAddress = effectiveIpAddress,
            effectivePort = hostPort,
            hostPort = Some(hostPort))
      }

    app.container.collect {
      case c: Container if app.networks.hasNonHostNetworking => fromPortMappings(c)
    }.getOrElse(fromPortDefinitions)
  }

}
