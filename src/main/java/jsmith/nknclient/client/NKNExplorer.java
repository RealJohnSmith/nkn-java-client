package jsmith.nknclient.client;

import com.darkyen.dave.WebbException;
import jsmith.nknclient.Const;
import jsmith.nknclient.utils.Base58;
import jsmith.nknclient.utils.Crypto;
import jsmith.nknclient.utils.HttpApi;
import jsmith.nknclient.wallet.WalletError;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigInteger;
import java.net.InetSocketAddress;

/**
 *
 */
public class NKNExplorer {

    private static final Logger LOG = LoggerFactory.getLogger(NKNExplorer.class);


    public static BigInteger queryBalance(String address) {
        return queryBalance(Const.BOOTSTRAP_NODES_RPC, address);
    }
    public static BigInteger queryBalance(InetSocketAddress bootstrapNodesRPC[], String address) {
        // Choose one node using round robin

        int bootstrapNodeIndex = (int)(Math.random() * bootstrapNodesRPC.length);
        InetSocketAddress bootstrapNodeRpc = bootstrapNodesRPC[bootstrapNodeIndex];
        int retries = Const.RETRIES;
        BigInteger result;
        WebbException error;
        do {
            try {
                result = HttpApi.getUTXO(bootstrapNodeRpc, address, Const.BALANCE_ASSET_ID);
                return result;
            } catch (WebbException e) {
                error = e;
                retries --;
                if (retries >= 0) {
                    LOG.warn("Query balance RPC request failed, remaining retries: {}", retries);
                } else {
                    LOG.warn("Query balance RPC request failed");
                }
            } catch (WalletError e) {
                LOG.warn("Failed to query balance", e);
                throw e;
            }
        } while (retries >= 0);

        throw new WalletError("Failed to query balance", error);
    }


    public static boolean isAddressValid(String address) {
        if (address.length() != 34) return false;
        try {

            final byte[] addressBytes = Base58.decode(address);
            if (addressBytes[0] != 53) return false;

            final byte[] sh = new byte[addressBytes.length - 4];
            System.arraycopy(addressBytes, 0, sh, 0, sh.length);

            final byte[] check = Crypto.doubleSha256(sh);
            for (int i = 0; i < 4; i++) {
                if (check[i] != addressBytes[sh.length + i]) return false;
            }

            return true;

        } catch (IllegalArgumentException e) { // Not Base58 input
            return false;
        }
    }

}