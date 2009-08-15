/**
 * Copyright 2009, Frederic Bregier, and individual contributors by the @author
 * tags. See the COPYRIGHT.txt in the distribution for a full listing of
 * individual contributors.
 *
 * This is free software; you can redistribute it and/or modify it under the
 * terms of the GNU Lesser General Public License as published by the Free
 * Software Foundation; either version 3.0 of the License, or (at your option)
 * any later version.
 *
 * This software is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU Lesser General Public License for more
 * details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this software; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA, or see the FSF
 * site: http://www.fsf.org.
 */
package goldengate.ftp.core.command.rfc2428;

import goldengate.common.command.ReplyCode;
import goldengate.common.command.exception.Reply425Exception;
import goldengate.common.command.exception.Reply501Exception;
import goldengate.common.logging.GgInternalLogger;
import goldengate.common.logging.GgInternalLoggerFactory;
import goldengate.ftp.core.command.AbstractCommand;
import goldengate.ftp.core.config.FtpInternalConfiguration;
import goldengate.ftp.core.data.FtpDataAsyncConn;
import goldengate.ftp.core.utils.FtpChannelUtils;

import java.net.InetAddress;
import java.net.InetSocketAddress;

/**
 * EPSV command
 *
 * @author Frederic Bregier
 *
 */
public class EPSV extends AbstractCommand {
    /**
     * Internal Logger
     */
    private static final GgInternalLogger logger = GgInternalLoggerFactory
            .getLogger(EPSV.class);

    /*
     * (non-Javadoc)
     *
     * @see goldengate.ftp.core.command.AbstractCommand#exec()
     */
    public void exec() throws Reply425Exception, Reply501Exception {
        // No Check if any argument
        // Take a new port: 3 attempts
        boolean isInit = false;
        int newport = -1;
        for (int i = 1; i <= FtpInternalConfiguration.RETRYNB; i ++) {
            newport = FtpDataAsyncConn.getNewPassivePort(getConfiguration());
            if (newport == -1) {
                throw new Reply425Exception("No port available");
            }
            if (getSession().getDataConn().isPassiveMode()) {
                // Previous mode was Passive so remove the current configuration
                InetSocketAddress local = getSession().getDataConn()
                        .getLocalAddress();
                InetAddress remote = getSession().getDataConn()
                        .getRemoteAddress().getAddress();
                getConfiguration().delFtpSession(remote, local);
            }
            logger.info("EPSV: set Passive Port {}", newport);
            getSession().getDataConn().setLocalPort(newport);
            getSession().getDataConn().setPassive();
            // Init the connection
            try {
                if (getSession().getDataConn().initPassiveConnection()) {
                    isInit = true;
                    break;
                }
            } catch (Reply425Exception e) {
                logger.warn("EPSV refused at try: " + i + " with port: " +
                        newport, e);
            }
        }
        if (!isInit) {
            throw new Reply425Exception("Extended Passive mode not started");
        }
        // Return the address in Ftp format
        InetSocketAddress local = getSession().getDataConn().getLocalAddress();
        String slocal = "Entering Extended Passive Mode (" +
                FtpChannelUtils.get2428Address(local) + ")";
        InetAddress remote = getSession().getDataConn().getRemoteAddress()
                .getAddress();
        // Add the current FtpSession into the reference of session since the
        // client will open the connection
        getConfiguration().setNewFtpSession(remote, local, getSession());
        getSession().setReplyCode(ReplyCode.REPLY_229_ENTERING_PASSIVE_MODE,
                "Entering Extended Passive Mode (|||" + newport + "|)");
        logger.info("EPSV: answer ready on {}", slocal);
        /*
         * Could be:this.getFtpSession().setReplyCode(ReplyCode.
         * REPLY_229_ENTERING_PASSIVE_MODE, slocal);
         */
    }

}
