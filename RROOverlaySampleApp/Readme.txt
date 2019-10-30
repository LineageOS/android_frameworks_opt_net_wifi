RROOverlaySampleApp:
a) This sample app demonstrates how OEMs can override any of the overlay flags
exposed by the wifi mainline module.
b) The list of values that can be overlayed are listed in service/res/values/overlayable.xml.
c) OEMs can add the flags they want to override in res/values/config.xml of this sample app
and put the sample app in either system, product or vendor partition of the device.
