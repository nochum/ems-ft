resource emsdata {
     protocol  C;
     device    /dev/drbd0;
     disk      /dev/ram0;
     meta-disk internal;
     on ip-10-0-10-184.ec2.internal {
           address 10.0.10.184:7788;
     }
     on ip-10-0-10-234.ec2.internal {
           address 10.0.10.234:7788;
     }
}

